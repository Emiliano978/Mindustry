package mindustry.game;

import arc.*;
import arc.assets.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.io.Streams.*;
import arc.util.pooling.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.Schematic.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.input.Placement.*;
import mindustry.io.*;
import mindustry.io.TypeIO.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.legacy.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.meta.*;

import java.io.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/** Handles schematics.*/
public class Schematics implements Loadable{
    private static final Schematic tmpSchem = new Schematic(new Seq<>(), new StringMap(), 0, 0);
    private static final Schematic tmpSchem2 = new Schematic(new Seq<>(), new StringMap(), 0, 0);

    private static final byte[] header = {'m', 's', 'c', 'h'};
    private static final byte version = 1;

    private static final int padding = 2;
    private static final int maxPreviewsMobile = 32;
    private static final int resolution = 32;

    private OptimizedByteArrayOutputStream out = new OptimizedByteArrayOutputStream(1024);
    private Seq<Schematic> all = new Seq<>();
    private OrderedMap<Schematic, FrameBuffer> previews = new OrderedMap<>();
    private ObjectSet<Schematic> errored = new ObjectSet<>();
    private ObjectMap<CoreBlock, Seq<Schematic>> loadouts = new ObjectMap<>();
    private ObjectMap<CoreBlock, Schematic> defaultLoadouts = new ObjectMap<>();
    private FrameBuffer shadowBuffer;
    private Texture errorTexture;
    private long lastClearTime;

    public Schematics(){
        Events.on(ClientLoadEvent.class, event -> {
            errorTexture = new Texture("sprites/error.png");
        });
    }

    @Override
    public void loadSync(){
        load();
    }

    /** Load all schematics in the folder immediately.*/
    public void load(){
        all.clear();

        loadLoadouts();

        schematicDirectory.walk(file -> {
            if(file.extEquals(schematicExtension)){
                loadFile(file);
            }
        });

        platform.getWorkshopContent(Schematic.class).each(this::loadFile);

        //mod-specific schematics, cannot be removed
        mods.listFiles("schematics", (mod, file) -> {
            Schematic s = loadFile(file);
            if(s != null){
                s.mod = mod;
            }
        });

        all.sort();

        if(shadowBuffer == null && !headless){
            Core.app.post(() -> shadowBuffer = new FrameBuffer(maxSchematicSize + padding + 8, maxSchematicSize + padding + 8));
        }
    }

    private void loadLoadouts(){
        Seq.with(Loadouts.basicShard, Loadouts.basicFoundation, Loadouts.basicNucleus, Loadouts.basicBastion).each(s -> checkLoadout(s, false));
    }

    public void overwrite(Schematic target, Schematic newSchematic){
        if(previews.containsKey(target)){
            previews.get(target).dispose();
            previews.remove(target);
        }

        target.tiles.clear();
        target.tiles.addAll(newSchematic.tiles);
        target.width = newSchematic.width;
        target.height = newSchematic.height;
        newSchematic.labels = target.labels;
        newSchematic.tags.putAll(target.tags);
        newSchematic.file = target.file;

        loadouts.each((block, list) -> list.remove(target));
        checkLoadout(target, true);

        try{
            write(newSchematic, target.file);
        }catch(Exception e){
            Log.err("Failed to overwrite schematic '@' (@)", newSchematic.name(), target.file);
            Log.err(e);
            ui.showException(e);
        }
    }

    private @Nullable Schematic loadFile(Fi file){
        if(!file.extension().equals(schematicExtension)) return null;

        try{
            Schematic s = read(file);
            all.add(s);
            checkLoadout(s, true);

            //external file from workshop
            if(!s.file.parent().equals(schematicDirectory)){
                s.tags.put("steamid", s.file.parent().name());
            }

            return s;
        }catch(Throwable e){
            Log.err("Failed to read schematic from file '@'", file);
            Log.err(e);
        }
        return null;
    }

    public Seq<Schematic> all(){
        return all;
    }

    public void saveChanges(Schematic s){
        if(s.file != null){
            try{
                write(s, s.file);
            }catch(Exception e){
                ui.showException(e);
            }
        }
        all.sort();
    }

    public void savePreview(Schematic schematic, Fi file){
        FrameBuffer buffer = getBuffer(schematic);
        Draw.flush();
        buffer.begin();
        Pixmap pixmap = ScreenUtils.getFrameBufferPixmap(0, 0, buffer.getWidth(), buffer.getHeight());
        file.writePng(pixmap);
        buffer.end();
    }

    public Texture getPreview(Schematic schematic){
        if(errored.contains(schematic)) return errorTexture;

        try{
            return getBuffer(schematic).getTexture();
        }catch(Throwable t){
            Log.err("Failed to get preview for schematic '@' (@)", schematic.name(), schematic.file);
            Log.err(t);
            errored.add(schematic);
            return errorTexture;
        }
    }

    public boolean hasPreview(Schematic schematic){
        return previews.containsKey(schematic);
    }

    public FrameBuffer getBuffer(Schematic schematic){
        //dispose unneeded previews to prevent memory outage errors.
        //only runs every 2 seconds
        if(mobile && Time.timeSinceMillis(lastClearTime) > 1000 * 2 && previews.size > maxPreviewsMobile){
            Seq<Schematic> keys = previews.orderedKeys().copy();
            for(int i = 0; i < previews.size - maxPreviewsMobile; i++){
                //dispose and remove unneeded previews
                previews.remove(keys.get(i)).dispose();
            }
            //update last clear time
            lastClearTime = Time.millis();
        }

        if(!previews.containsKey(schematic)){
            Draw.blend();
            Draw.reset();
            Tmp.m1.set(Draw.proj());
            Tmp.m2.set(Draw.trans());
            FrameBuffer buffer = new FrameBuffer((schematic.width + padding) * resolution, (schematic.height + padding) * resolution);

            shadowBuffer.begin(Color.clear);

            Draw.trans().idt();
            Draw.proj().setOrtho(0, 0, shadowBuffer.getWidth(), shadowBuffer.getHeight());

            Draw.color();
            schematic.tiles.each(t -> {
                int size = t.block.size;
                int offsetx = -(size - 1) / 2;
                int offsety = -(size - 1) / 2;
                for(int dx = 0; dx < size; dx++){
                    for(int dy = 0; dy < size; dy++){
                        int wx = t.x + dx + offsetx;
                        int wy = t.y + dy + offsety;
                        Fill.square(padding/2f + wx + 0.5f, padding/2f + wy + 0.5f, 0.5f);
                    }
                }
            });

            shadowBuffer.end();

            buffer.begin(Color.clear);

            Draw.proj().setOrtho(0, buffer.getHeight(), buffer.getWidth(), -buffer.getHeight());

            Tmp.tr1.set(shadowBuffer.getTexture(), 0, 0, schematic.width + padding, schematic.height + padding);
            Draw.color(0f, 0f, 0f, 1f);
            Draw.rect(Tmp.tr1, buffer.getWidth()/2f, buffer.getHeight()/2f, buffer.getWidth(), -buffer.getHeight());
            Draw.color();

            Seq<BuildPlan> plans = schematic.tiles.map(t -> new BuildPlan(t.x, t.y, t.rotation, t.block, t.config));

            Draw.flush();
            //scale each plan to fit schematic
            Draw.trans().scale(resolution / tilesize, resolution / tilesize).translate(tilesize*1.5f, tilesize*1.5f);

            //draw plans
            plans.each(req -> {
                req.animScale = 1f;
                req.worldContext = false;
                req.block.drawPlanRegion(req, plans);
            });

            plans.each(req -> req.block.drawPlanConfigTop(req, plans));

            Draw.flush();
            Draw.trans().idt();

            buffer.end();

            Draw.proj(Tmp.m1);
            Draw.trans(Tmp.m2);

            previews.put(schematic, buffer);
        }

        return previews.get(schematic);
    }

    /** Creates an array of build plans from a schematic's data, centered on the provided x+y coordinates. */
    public Seq<BuildPlan> toPlans(Schematic schem, int x, int y){
        return schem.tiles.map(t -> new BuildPlan(t.x + x - schem.width/2, t.y + y - schem.height/2, t.rotation, t.block, t.config))
            .removeAll(s -> (!s.block.isVisible() && !(s.block instanceof CoreBlock)) || !s.block.unlockedNow()).sort(Structs.comparingInt(s -> -s.block.schematicPriority));
    }

    /** @return all the valid loadouts for a specific core type. */
    public Seq<Schematic> getLoadouts(CoreBlock block){
        return loadouts.get(block, Seq::new);
    }

    public ObjectMap<CoreBlock, Seq<Schematic>> getLoadouts(){
        return loadouts;
    }

    public @Nullable Schematic getDefaultLoadout(CoreBlock block){
        return defaultLoadouts.get(block);
    }

    public boolean isDefaultLoadout(Schematic schem){
        return defaultLoadouts.containsValue(schem, true);
    }

    /** Checks a schematic for deployment validity and adds it to the cache. */
    private void checkLoadout(Schematic s, boolean customSchem){
        Stile core = s.tiles.find(t -> t.block instanceof CoreBlock);
        if(core == null) return;
        int cores = s.tiles.count(t -> t.block instanceof CoreBlock);
        int maxSize = getMaxLaunchSize(core.block);

        //make sure a core exists, and that the schematic is small enough.
        if((customSchem && (s.width > maxSize || s.height > maxSize
            || s.tiles.contains(t -> t.block.buildVisibility == BuildVisibility.sandboxOnly || !t.block.unlocked()) || cores > 1))) return;

        //place in the cache
        loadouts.get((CoreBlock)core.block, Seq::new).add(s);

        //save non-custom loadout
        if(!customSchem){
            defaultLoadouts.put((CoreBlock)core.block, s);
        }
    }

    public int getMaxLaunchSize(Block block){
        return block.size + maxLoadoutSchematicPad*2;
    }

    Fi findFile(String schematicName){
        if(schematicName.isEmpty()) schematicName = "empty";
        Fi result = null;
        int index = 0;

        while(result == null || result.exists()){
            result = schematicDirectory.child(schematicName + (index == 0 ? "" : "_" + index) + "." + schematicExtension);
            index ++;
        }

        return result;
    }

    /** Adds a schematic to the list, also copying it into the files.*/
    public void add(Schematic schematic){
        all.add(schematic);
        try{
            Fi file = findFile(Strings.sanitizeFilename(schematic.name()));
            write(schematic, file);
            schematic.file = file;
        }catch(Exception e){
            ui.showException(e);
            Log.err(e);
        }

        checkLoadout(schematic, true);
        all.sort();
    }

    public void remove(Schematic s){
        all.remove(s);
        loadouts.each((block, seq) -> seq.remove(s));
        if(s.file != null){
            s.file.delete();
        }

        if(previews.containsKey(s)){
            previews.get(s).dispose();
            previews.remove(s);
        }
        all.sort();
    }

    /** Creates a schematic from a world selection. */
    public Schematic create(int x, int y, int x2, int y2){
        Team team = headless ? null : Vars.player.team();
        NormalizeResult result = Placement.normalizeArea(x, y, x2, y2, 0, false, maxSchematicSize);
        x = result.x;
        y = result.y;
        x2 = result.x2;
        y2 = result.y2;

        int ox = x, oy = y, ox2 = x2, oy2 = y2;

        Seq<Stile> tiles = new Seq<>();

        int minx = x2, miny = y2, maxx = x, maxy = y;
        boolean found = false;
        for(int cx = x; cx <= x2; cx++){
            for(int cy = y; cy <= y2; cy++){
                Building linked = world.build(cx, cy);
                if(linked != null && (!linked.isDiscovered(team) || !linked.wasVisible)) continue;

                Block realBlock = linked == null ? null : linked instanceof ConstructBuild cons ? cons.current : linked.block;

                if(linked != null && realBlock != null && (realBlock.isVisible() || realBlock instanceof CoreBlock)){
                    int top = realBlock.size/2;
                    int bot = realBlock.size % 2 == 1 ? -realBlock.size/2 : -(realBlock.size - 1)/2;
                    minx = Math.min(linked.tileX() + bot, minx);
                    miny = Math.min(linked.tileY() + bot, miny);
                    maxx = Math.max(linked.tileX() + top, maxx);
                    maxy = Math.max(linked.tileY() + top, maxy);
                    found = true;
                }
            }
        }

        if(found){
            x = minx;
            y = miny;
            x2 = maxx;
            y2 = maxy;
        }else{
            return new Schematic(new Seq<>(), new StringMap(), 1, 1);
        }

        int width = x2 - x + 1, height = y2 - y + 1;
        int offsetX = -x, offsetY = -y;
        IntSet counted = new IntSet();
        for(int cx = ox; cx <= ox2; cx++){
            for(int cy = oy; cy <= oy2; cy++){
                Building tile = world.build(cx, cy);
                if(tile != null && (!tile.isDiscovered(team) || !tile.wasVisible)) continue;
                Block realBlock = tile == null ? null : tile instanceof ConstructBuild cons ? cons.current : tile.block;

                if(tile != null && !counted.contains(tile.pos()) && realBlock != null
                    && (realBlock.isVisible() || realBlock instanceof CoreBlock)){
                    Object config = tile instanceof ConstructBuild cons ? cons.lastConfig : tile.config();

                    tiles.add(new Stile(realBlock, tile.tileX() + offsetX, tile.tileY() + offsetY, config, (byte)tile.rotation));
                    counted.add(tile.pos());
                }
            }
        }

        return new Schematic(tiles, new StringMap(), width, height);
    }

    /** Converts a schematic to base64. Note that the result of this will always start with 'bXNjaAB'.*/
    public String writeBase64(Schematic schematic){
        try{
            out.reset();
            write(schematic, out);
            return new String(Base64Coder.encode(out.getBuffer(), out.size()));
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    /** Places the last launch loadout at the coordinates and fills it with the launch resources. */
    public static void placeLaunchLoadout(int x, int y){
        placeLoadout(universe.getLastLoadout(), x, y, state.rules.defaultTeam);
        if(world.tile(x, y).build == null) throw new RuntimeException("No core at loadout coordinates!");
        world.tile(x, y).build.items.add(universe.getLaunchResources());
    }

    public static void placeLoadout(Schematic schem, int x, int y){
        placeLoadout(schem, x, y, state.rules.defaultTeam);
    }

    public static void placeLoadout(Schematic schem, int x, int y, Team team){
        placeLoadout(schem, x, y, team, true);
    }

    public static void placeLoadout(Schematic schem, int x, int y, Team team, boolean check){
        Stile coreTile = schem.tiles.find(s -> s.block instanceof CoreBlock);
        Seq<Tile> seq = new Seq<>();
        if(coreTile == null) throw new IllegalArgumentException("Loadout schematic has no core tile!");
        int ox = x - coreTile.x, oy = y - coreTile.y;
        schem.tiles.copy().sort(s -> -s.block.schematicPriority).each(st -> {
            Tile tile = world.tile(st.x + ox, st.y + oy);
            if(tile == null) return;

            //check for blocks that are in the way.
            if(check && !(st.block instanceof CoreBlock)){
                seq.clear();
                tile.getLinkedTilesAs(st.block, seq);
                //remove env blocks, or not?
                //if(seq.contains(t -> !t.block().alwaysReplace && !t.synthetic())){
                //    return;
                //}
                for(var t : seq){
                    if(t.block() != Blocks.air){
                        t.remove();
                    }
                }
            }

            tile.setBlock(st.block, team, st.rotation);

            Object config = st.config;
            if(tile.build != null){
                tile.build.configureAny(config);
            }

            if(tile.build instanceof CoreBuild cb){
                state.teams.registerCore(cb);
            }
        });
    }

    public static void place(Schematic schem, int x, int y, Team team){
        place(schem, x, y, team, true);
    }

    public static void place(Schematic schem, int x, int y, Team team, boolean overwrite){
        int ox = x - schem.width/2, oy = y - schem.height/2;
        schem.tiles.each(st -> {
            Tile tile = world.tile(st.x + ox, st.y + oy);
            if(tile == null || (!overwrite && !Build.validPlace(st.block, team, tile.x, tile.y, st.rotation))) return;

            tile.setBlock(st.block, team, st.rotation);

            Object config = st.config;
            if(tile.build != null){
                tile.build.configureAny(config);
            }
        });
    }

    //region IO methods

    /** Loads a schematic from base64. May throw an exception. */
    public static Schematic readBase64(String schematic){
        try{
            return read(new ByteArrayInputStream(Base64Coder.decode(schematic.trim())));
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static Schematic read(Fi file) throws IOException{
        Schematic s = read(new DataInputStream(file.read(1024)));
        if(!s.tags.containsKey("name")){
            s.tags.put("name", file.nameWithoutExtension());
        }
        s.file = file;
        return s;
    }

    public static Schematic read(InputStream input) throws IOException{
        for(byte b : header){
            if(input.read() != b){
                throw new IOException("Not a schematic file (missing header).");
            }
        }

        int ver = input.read();

        if(ver > version) throw new IOException("Unknown version: " + ver + " (are you trying to load a schematic from a newer version of the game?)");

        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(input))){
            short width = stream.readShort(), height = stream.readShort();

            if(width > 128 || height > 128) throw new IOException("Invalid schematic: Too large (max possible size is 128x128)");

            StringMap map = new StringMap();
            int tags = stream.readUnsignedByte();
            for(int i = 0; i < tags; i++){
                map.put(stream.readUTF(), stream.readUTF());
            }

            ContentMapper mapper = null;

            //set up content mapping if found; this should not fail
            if(map.containsKey("contentMap")){
                IntMap<ObjectIntMap<String>> nameMap = JsonIO.json.fromJson(IntMap.class, ObjectIntMap.class, map.get("contentMap", "{}"));
                IntMap<IntMap<Content>> contentMap = new IntMap<>();
                for(var entry : nameMap){
                    var inner = new IntMap<Content>();
                    contentMap.put(entry.key, inner);
                    for(var ce : entry.value){
                        inner.put(ce.value, content.getByName(ContentType.all[entry.key], ce.key));
                    }
                }
                mapper = (type, id) -> contentMap.get(type.ordinal(), IntMap::new).get(id);
            }

            String[] labels = null;

            //try to read the categories, but skip if it fails
            try{
                labels = JsonIO.read(String[].class, map.get("labels", "[]"));
            }catch(Exception ignored){}

            IntMap<Block> blocks = new IntMap<>();
            int length = stream.readUnsignedByte();
            for(int i = 0; i < length; i++){
                String name = stream.readUTF();
                Block block = Vars.content.getByName(ContentType.block, SaveFileReader.fallback.get(name, name));
                blocks.put(i, block == null || block instanceof LegacyBlock ? Blocks.air : block);
            }

            int total = stream.readInt();

            if(total > 128 * 128) throw new IOException("Invalid schematic: Too many blocks.");

            Seq<Stile> tiles = new Seq<>(total);
            for(int i = 0; i < total; i++){
                Block block = blocks.get(stream.readByte());
                int position = stream.readInt();
                Object config = ver == 0 ? mapConfig(block, stream.readInt(), position) : TypeIO.readObject(Reads.get(stream), false, mapper);
                byte rotation = stream.readByte();
                if(block != Blocks.air){
                    tiles.add(new Stile(block, Point2.x(position), Point2.y(position), config, rotation));
                }
            }

            Schematic out = new Schematic(tiles, map, width, height);
            if(labels != null) out.labels.addAll(labels);
            return out;
        }
    }

    public static void write(Schematic schematic, Fi file) throws IOException{
        write(schematic, file.write(false, 1024));
    }

    public static void write(Schematic schematic, OutputStream output) throws IOException{
        output.write(header);
        output.write(version);

        try(DataOutputStream stream = new DataOutputStream(new DeflaterOutputStream(output))){

            stream.writeShort(schematic.width);
            stream.writeShort(schematic.height);

            schematic.tags.put("labels", JsonIO.write(schematic.labels.toArray(String.class)));

            //write a map for content name -> id to make sure remapping doesn't occur
            IntMap<ObjectIntMap<String>> contentMap = new IntMap<>();
            for(var tile : schematic.tiles){
                if(tile.config instanceof MappableContent c){
                    contentMap.get(c.getContentType().ordinal(), ObjectIntMap::new).put(c.name, c.id);
                }
            }

            schematic.tags.put("contentMap", JsonIO.write(contentMap));

            stream.writeByte(schematic.tags.size);
            for(var e : schematic.tags.entries()){
                stream.writeUTF(e.key);
                stream.writeUTF(e.value);
            }

            OrderedSet<Block> blocks = new OrderedSet<>();
            schematic.tiles.each(t -> blocks.add(t.block));

            //create dictionary
            stream.writeByte(blocks.size);
            for(int i = 0; i < blocks.size; i++){
                stream.writeUTF(blocks.orderedItems().get(i).name);
            }

            stream.writeInt(schematic.tiles.size);
            //write each tile
            for(Stile tile : schematic.tiles){
                stream.writeByte(blocks.orderedItems().indexOf(tile.block));
                stream.writeInt(Point2.pack(tile.x, tile.y));
                TypeIO.writeObject(Writes.get(stream), tile.config);
                stream.writeByte(tile.rotation);
            }
        }
    }

    /** Maps legacy int configs to new config objects. */
    private static Object mapConfig(Block block, int value, int position){
        if(block instanceof Sorter || block instanceof Unloader || block instanceof ItemSource) return content.item(value);
        if(block instanceof LiquidSource) return content.liquid(value);
        if(block instanceof MassDriver || block instanceof ItemBridge) return Point2.unpack(value).sub(Point2.x(position), Point2.y(position));
        if(block instanceof LightBlock) return value;

        return null;
    }

    //endregion
    //region misc utility

    /** @return a temporary schematic representing the input rotated 90 degrees counterclockwise N times. */
    public static Schematic rotate(Schematic input, int times){
        if(times == 0) return input;

        boolean sign = times > 0;
        for(int i = 0; i < Math.abs(times); i++){
            input = rotated(input, sign);
        }
        return input;
    }

    private static Schematic rotated(Schematic input, boolean counter){
        int direction = Mathf.sign(counter);
        Schematic schem = input == tmpSchem ? tmpSchem2 : tmpSchem;
        schem.width = input.width;
        schem.height = input.height;
        Pools.freeAll(schem.tiles);
        schem.tiles.clear();
        for(Stile tile : input.tiles){
            schem.tiles.add(Pools.obtain(Stile.class, Stile::new).set(tile));
        }

        int ox = schem.width/2, oy = schem.height/2;

        schem.tiles.each(req -> {
            req.config = BuildPlan.pointConfig(req.block, req.config, p -> {
                int cx = p.x, cy = p.y;
                int lx = cx;

                if(direction >= 0){
                    cx = -cy;
                    cy = lx;
                }else{
                    cx = cy;
                    cy = -lx;
                }
                p.set(cx, cy);
            });

            //rotate actual plan, centered on its multiblock position
            float wx = (req.x - ox) * tilesize + req.block.offset, wy = (req.y - oy) * tilesize + req.block.offset;
            float x = wx;
            if(direction >= 0){
                wx = -wy;
                wy = x;
            }else{
                wx = wy;
                wy = -x;
            }
            req.x = (short)(World.toTile(wx - req.block.offset) + ox);
            req.y = (short)(World.toTile(wy - req.block.offset) + oy);
            req.rotation = (byte)Mathf.mod(req.rotation + direction, 4);
        });

        //assign flipped values, since it's rotated
        schem.width = input.height;
        schem.height = input.width;

        return schem;
    }

    //endregion
}
