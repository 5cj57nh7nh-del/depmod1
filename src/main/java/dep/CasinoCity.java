package dep;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PaneBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.text.Text;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Ночной город казино 99x99. Пол измерения на y=4, ходим по y=5.
 * Центр (0,0): площадь с золотым центром, четыре здания по углам:
 *  (+,+) зал слотов, (+,-) рулетка, (-,+) долговая тюрьма, (-,-) бар.
 */
public final class CasinoCity {
    private static final int FLOOR = 4;
    private static ServerWorld W;
    private static final BlockPos.Mutable M = new BlockPos.Mutable();

    private CasinoCity() {}

    public static boolean isBuilt(ServerWorld w) {
        return w.getBlockState(new BlockPos(0, FLOOR, 0)).isOf(Blocks.GOLD_BLOCK);
    }

    // ----- примитивы -----
    private static void set(int x, int y, int z, BlockState s) {
        W.setBlockState(M.set(x, y, z), s, Block.NOTIFY_LISTENERS);
    }
    private static void set(int x, int y, int z, Block b) { set(x, y, z, b.getDefaultState()); }

    private static void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, b);
    }

    // координаты квартала: (u,v) -> (sx*u, sz*v)
    private static void q(int sx, int sz, int u, int y, int v, Block b) { set(sx * u, y, sz * v, b); }
    private static void q(int sx, int sz, int u, int y, int v, BlockState s) { set(sx * u, y, sz * v, s); }

    private static void qFill(int sx, int sz, int u1, int y1, int v1, int u2, int y2, int v2, Block b) {
        for (int u = u1; u <= u2; u++)
            for (int y = y1; y <= y2; y++)
                for (int v = v1; v <= v2; v++)
                    q(sx, sz, u, y, v, b);
    }

    /** Решётка со всеми соединениями, чтобы между прутьями нельзя было пролезть. */
    private static BlockState bars() {
        return Blocks.IRON_BARS.getDefaultState()
                .with(PaneBlock.NORTH, true).with(PaneBlock.SOUTH, true)
                .with(PaneBlock.EAST, true).with(PaneBlock.WEST, true);
    }

    private static void lamp(int x, int z) {
        fill(x, 5, z, x, 8, z, Blocks.IRON_BARS);
        set(x, 9, z, Blocks.SEA_LANTERN);
    }

    // ----- здание -----
    private static void shell(int sx, int sz, int u1, int v1, int u2, int v2, int h,
                              Block wall, Block floor, Block carpet, Block light) {
        int top = FLOOR + h;
        qFill(sx, sz, u1, FLOOR, v1, u2, FLOOR, v2, floor);
        qFill(sx, sz, u1, FLOOR + 1, v1, u2, top, v2, wall);
        qFill(sx, sz, u1 + 1, FLOOR + 1, v1 + 1, u2 - 1, top, v2 - 1, Blocks.AIR);
        qFill(sx, sz, u1, top + 1, v1, u2, top + 1, v2, wall);
        for (int y = FLOOR + 2; y <= FLOOR + 3; y++) {
            for (int u = u1 + 1; u <= u2 - 1; u++) if (u % 3 != 0) {
                q(sx, sz, u, y, v1, Blocks.PURPLE_STAINED_GLASS);
                q(sx, sz, u, y, v2, Blocks.PURPLE_STAINED_GLASS);
            }
            for (int v = v1 + 1; v <= v2 - 1; v++) if (v % 3 != 0) {
                q(sx, sz, u1, y, v, Blocks.PURPLE_STAINED_GLASS);
                q(sx, sz, u2, y, v, Blocks.PURPLE_STAINED_GLASS);
            }
        }
        for (int u = u1; u <= u2; u++) { q(sx, sz, u, top, v1, light); q(sx, sz, u, top, v2, light); }
        for (int v = v1; v <= v2; v++) { q(sx, sz, u1, top, v, light); q(sx, sz, u2, top, v, light); }
        for (int u = u1 + 2; u <= u2 - 2; u++)
            for (int v = v1 + 2; v <= v2 - 2; v++)
                if (u % 4 == 0 && v % 4 == 0) q(sx, sz, u, top, v, light);
        qFill(sx, sz, u1 + 1, FLOOR + 1, v1 + 1, u2 - 1, FLOOR + 1, v2 - 1, carpet);
        int vm = (v1 + v2) / 2;
        qFill(sx, sz, u1, FLOOR + 1, vm - 1, u1, FLOOR + 3, vm + 1, Blocks.AIR);
    }

    // ----- весь город -----
    public static void build(ServerWorld w) {
        W = w;

        // дороги
        fill(-49, FLOOR, -3, 49, FLOOR, 3, Blocks.GRAY_CONCRETE);
        fill(-3, FLOOR, -49, 3, FLOOR, 49, Blocks.GRAY_CONCRETE);
        for (int i = -48; i <= 48; i += 4) {
            if (Math.abs(i) > 10) { set(i, FLOOR, 0, Blocks.YELLOW_CONCRETE); set(0, FLOOR, i, Blocks.YELLOW_CONCRETE); }
        }

        // площадь
        for (int x = -10; x <= 10; x++)
            for (int z = -10; z <= 10; z++)
                set(x, FLOOR, z, ((x + z) & 1) == 0 ? Blocks.GOLD_BLOCK : Blocks.RED_CONCRETE);

        // монумент
        fill(-1, 5, -1, 1, 6, 1, Blocks.GOLD_BLOCK);
        fill(0, 7, 0, 0, 14, 0, Blocks.GOLD_BLOCK);
        fill(-1, 15, -1, 1, 15, 1, Blocks.SEA_LANTERN);
        fill(0, 16, 0, 0, 18, 0, Blocks.END_ROD);

        // фонари
        for (int i = -44; i <= 44; i += 8) {
            if (Math.abs(i) < 12) continue;
            lamp(i, 4); lamp(i, -4); lamp(4, i); lamp(-4, i);
        }

        // городская стена
        for (int i = -49; i <= 49; i++) {
            for (int y = 5; y <= 13; y++) {
                Block b = (y == 13 || (y == 9 && i % 6 == 0)) ? Blocks.SEA_LANTERN : Blocks.BLACK_CONCRETE;
                set(i, y, 49, b); set(i, y, -49, b); set(49, y, i, b); set(-49, y, i, b);
            }
        }

        // зал слотов (+,+)
        shell(1, 1, 8, 8, 30, 30, 7, Blocks.PURPLE_CONCRETE, Blocks.POLISHED_BLACKSTONE, Blocks.RED_CARPET, Blocks.SEA_LANTERN);
        int[] rowsU = {12, 16, 20, 24, 28};
        int[] rowsV = {11, 13, 15, 23, 25, 27};
        for (int u : rowsU) for (int v : rowsV) q(1, 1, u, 5, v, DepMod.SLOT_MACHINE);

        // рулетка (+,-)
        shell(1, -1, 8, 8, 30, 30, 7, Blocks.BLUE_CONCRETE, Blocks.POLISHED_BLACKSTONE, Blocks.GREEN_CARPET, Blocks.SEA_LANTERN);
        int[] rt = {13, 19, 25};
        for (int u : rt) for (int v : rt) q(1, -1, u, 5, v, DepMod.ROULETTE);

        // долговая тюрьма (-,+)
        shell(-1, 1, 8, 8, 30, 30, 7, Blocks.STONE_BRICKS, Blocks.POLISHED_BLACKSTONE, Blocks.AIR, Blocks.SHROOMLIGHT);
        for (int y = 5; y <= 8; y++) {
            for (int i = 17; i <= 21; i++) {
                q(-1, 1, i, y, 17, bars()); q(-1, 1, i, y, 21, bars());
                q(-1, 1, 17, y, i, bars()); q(-1, 1, 21, y, i, bars());
            }
        }
        for (int u = 17; u <= 21; u++) for (int v = 17; v <= 21; v++) q(-1, 1, u, 9, v, bars());
        q(-1, 1, 19, 5, 19, DepMod.DEBT_PIT);
        if (!DepMod.hasPit()) DepMod.setPit(w, new BlockPos(-19, 6, 19));

        // бар (-,-)
        shell(-1, -1, 8, 8, 30, 30, 7, Blocks.SPRUCE_PLANKS, Blocks.POLISHED_BLACKSTONE, Blocks.BROWN_CARPET, Blocks.SEA_LANTERN);
        qFill(-1, -1, 10, 5, 25, 28, 5, 25, Blocks.DARK_OAK_PLANKS);
        qFill(-1, -1, 10, 6, 25, 28, 6, 25, Blocks.DARK_OAK_SLAB);
        qFill(-1, -1, 10, 6, 29, 28, 8, 29, Blocks.BOOKSHELF);
        int[] tu = {12, 16, 24};
        int[] tv = {12, 16, 22};
        for (int u : tu) for (int v : tv) {
            q(-1, -1, u, 5, v, Blocks.DARK_OAK_FENCE);
            q(-1, -1, u, 6, v, Blocks.DARK_OAK_PRESSURE_PLATE);
        }

        // крупье (дилеры блэкджека)
        croupier(w, 3.5, 5, 3.5, 135f);
        croupier(w, -3.5, 5, -3.5, -45f);
        croupier(w, 15.5, 5, 19.5, 90f);
        croupier(w, 16.5, 5, -16.5, 90f);
    }

    /** Неубиваемый житель без ИИ. ПКМ по нему — партия в блэкджек. */
    public static void croupier(ServerWorld w, double x, double y, double z, float yaw) {
        VillagerEntity v = EntityType.VILLAGER.create(w);
        if (v == null) return;
        v.refreshPositionAndAngles(x, y, z, yaw, 0f);
        v.setCustomName(Text.literal("Крупье | Блэкджек"));
        v.setCustomNameVisible(true);
        v.setAiDisabled(true);
        v.setInvulnerable(true);
        v.setPersistent();
        v.setSilent(true);
        v.addCommandTag("dep_croupier");
        w.spawnEntity(v);
    }
}
