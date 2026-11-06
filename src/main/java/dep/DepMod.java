package dep;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.text.ClickEvent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import java.util.function.Consumer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class DepMod implements ModInitializer {
    public static final String ID = "dep";
    public static final int BAIL_CHIPS = 16;   // выкуп из ямы
    public static final int SLOT_JACKPOT = 10; // выигрыш за три одинаковых

    public static Block SLOT_MACHINE, ROULETTE, DEBT_PIT;
    public static Item CHIP, TICKET;
    public static final RegistryKey<World> CASINO = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(ID, "casino"));

    /** ставки: кто играет -> кого депает */
    private static final Map<UUID, UUID> STAKES = new HashMap<>();
    /** кто сидит в яме -> откуда его забрали */
    private static final Map<UUID, Saved> JAILED = new HashMap<>();
    /** кто запретил себя депать */
    private static final Set<UUID> OPTOUT = new HashSet<>();
    private static Saved pit;
    /** кто уже получал стартовые фишки */
    private static final Set<UUID> VISITED = new HashSet<>();
    /** откуда человек пришёл в казино */
    private static final Map<UUID, Saved> RETURN = new HashMap<>();

    record Saved(RegistryKey<World> dim, double x, double y, double z) {
        static Saved of(ServerPlayerEntity p) {
            return new Saved(p.getServerWorld().getRegistryKey(), p.getX(), p.getY(), p.getZ());
        }
        void tp(MinecraftServer server, ServerPlayerEntity p) {
            ServerWorld w = server.getWorld(dim);
            if (w != null) p.teleport(w, x, y, z, p.getYaw(), p.getPitch());
        }
        String ser() { return dim.getValue() + " " + x + " " + y + " " + z; }
        static Saved parse(String[] t, int i) {
            return new Saved(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(t[i])),
                    Double.parseDouble(t[i + 1]), Double.parseDouble(t[i + 2]), Double.parseDouble(t[i + 3]));
        }
    }

    // ---------- регистрация ----------
    private static Block block(String name, Block b) {
        Identifier id = Identifier.of(ID, name);
        Registry.register(Registries.BLOCK, id, b);
        Registry.register(Registries.ITEM, id, new BlockItem(b, new Item.Settings()));
        return b;
    }

    @Override
    public void onInitialize() {
        SLOT_MACHINE = block("slot_machine", new SlotMachineBlock(AbstractBlock.Settings.create().strength(2.0f)));
        ROULETTE = block("roulette", new RouletteBlock(AbstractBlock.Settings.create().strength(2.0f)));
        DEBT_PIT = block("debt_pit", new DebtPitBlock(AbstractBlock.Settings.create().strength(3.0f)));
        CHIP = Registry.register(Registries.ITEM, Identifier.of(ID, "chip"), new Item(new Item.Settings()));
        TICKET = Registry.register(Registries.ITEM, Identifier.of(ID, "ticket"), new TicketItem(new Item.Settings().maxCount(16)));

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(e -> {
            e.add(SLOT_MACHINE); e.add(ROULETTE); e.add(DEBT_PIT); e.add(CHIP); e.add(TICKET);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(s -> { load(); TASKS.clear(); GAMES.clear(); BUSY.clear(); });
        ServerTickEvents.END_SERVER_TICK.register(s -> runTasks());
        registerProtection();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            // /dep <игрок> — поставить друга на кон
            dispatcher.register(CommandManager.literal("dep")
                    .then(CommandManager.argument("target", EntityArgumentType.player())
                            .executes(ctx -> {
                                ServerPlayerEntity me = ctx.getSource().getPlayerOrThrow();
                                ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "target");
                                if (t.getUuid().equals(me.getUuid())) {
                                    ctx.getSource().sendError(Text.literal("Себя депать нельзя, это уже диагноз."));
                                    return 0;
                                }
                                if (OPTOUT.contains(t.getUuid())) {
                                    ctx.getSource().sendError(Text.literal(t.getName().getString() + " запретил себя депать."));
                                    return 0;
                                }
                                if (JAILED.containsKey(t.getUuid())) {
                                    ctx.getSource().sendError(Text.literal(t.getName().getString() + " уже в долговой яме."));
                                    return 0;
                                }
                                STAKES.put(me.getUuid(), t.getUuid());
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                        "На кон поставлен " + t.getName().getString() + ". Жми ПКМ по автомату или рулетке!"), false);
                                return 1;
                            })));

            // /blackjack hit|stand — кнопки в чате блэкджека
            dispatcher.register(CommandManager.literal("blackjack")
                    .then(CommandManager.literal("hit").executes(ctx -> { bjHit(ctx.getSource().getPlayerOrThrow()); return 1; }))
                    .then(CommandManager.literal("stand").executes(ctx -> { bjStand(ctx.getSource().getPlayerOrThrow()); return 1; })));

            dispatcher.register(CommandManager.literal("casino")
                    // /casino bail <игрок> — выкупить из ямы за фишки (хоть себя, хоть друга)
                    .then(CommandManager.literal("bail")
                            .then(CommandManager.argument("target", EntityArgumentType.player())
                                    .executes(ctx -> {
                                        ServerPlayerEntity payer = ctx.getSource().getPlayerOrThrow();
                                        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "target");
                                        if (!JAILED.containsKey(t.getUuid())) {
                                            ctx.getSource().sendError(Text.literal("Он не в яме."));
                                            return 0;
                                        }
                                        if (!take(payer, CHIP, BAIL_CHIPS)) {
                                            ctx.getSource().sendError(Text.literal("Выкуп стоит " + BAIL_CHIPS + " фишек."));
                                            return 0;
                                        }
                                        release(ctx.getSource().getServer(), t);
                                        broadcast(ctx.getSource().getServer(), payer.getName().getString()
                                                + " выкупил " + t.getName().getString() + " из долговой ямы за " + BAIL_CHIPS + " фишек.");
                                        return 1;
                                    })))
                    // /casino free <игрок> — простить долг (только оп)
                    .then(CommandManager.literal("free")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(CommandManager.argument("target", EntityArgumentType.player())
                                    .executes(ctx -> {
                                        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "target");
                                        if (!JAILED.containsKey(t.getUuid())) {
                                            ctx.getSource().sendError(Text.literal("Он не в яме."));
                                            return 0;
                                        }
                                        release(ctx.getSource().getServer(), t);
                                        t.sendMessage(Text.literal("Долг прощён. Ты свободен."), false);
                                        return 1;
                                    })))
                    // /casino setpit — задать яму вручную (только оп; обычно хватает блока «Долговая яма»)
                    .then(CommandManager.literal("setpit")
                            .requires(s -> s.hasPermissionLevel(2))
                            .executes(ctx -> {
                                pit = Saved.of(ctx.getSource().getPlayerOrThrow());
                                save();
                                ctx.getSource().sendFeedback(() -> Text.literal("Долговая яма задана здесь."), true);
                                return 1;
                            }))
                    // /casino croupier — поставить крупье на своё место (только оп)
                    .then(CommandManager.literal("croupier")
                            .requires(s -> s.hasPermissionLevel(2))
                            .executes(ctx -> {
                                ServerPlayerEntity me = ctx.getSource().getPlayerOrThrow();
                                CasinoCity.croupier(me.getServerWorld(), me.getX(), me.getY(), me.getZ(), me.getYaw() + 180f);
                                return 1;
                            }))
                    // /casino tp — в город казино (только оп; обычно — по билету)
                    .then(CommandManager.literal("tp")
                            .requires(s -> s.hasPermissionLevel(2))
                            .executes(ctx -> { toCasino(ctx.getSource().getPlayerOrThrow()); return 1; }))
                    // /casino home — вернуться из казино
                    .then(CommandManager.literal("home")
                            .executes(ctx -> { toHome(ctx.getSource().getPlayerOrThrow()); return 1; }))
                    // /casino optout — запретить друзьям депать тебя
                    .then(CommandManager.literal("optout")
                            .executes(ctx -> {
                                ServerPlayerEntity me = ctx.getSource().getPlayerOrThrow();
                                boolean nowOut = OPTOUT.add(me.getUuid());
                                if (!nowOut) OPTOUT.remove(me.getUuid());
                                save();
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                        nowOut ? "Тебя больше нельзя депать." : "Тебя снова можно депать."), false);
                                return 1;
                            })));
        });
    }

    // ---------- мир казино ----------
    public static boolean hasPit() { return pit != null; }

    public static boolean toCasino(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        ServerWorld w = s.getWorld(CASINO);
        if (w == null) {
            p.sendMessage(Text.literal("Мир казино не найден. Он есть в новых мирах; для старого включи датапак мода."), false);
            return false;
        }
        if (p.getServerWorld().getRegistryKey().equals(CASINO)) {
            p.sendMessage(Text.literal("Ты уже в казино."), false);
            return false;
        }
        if (JAILED.containsKey(p.getUuid())) {
            p.sendMessage(Text.literal("Из долговой ямы билетом не уйдёшь."), false);
            return false;
        }
        if (!CasinoCity.isBuilt(w)) {
            p.sendMessage(Text.literal("Строю город казино, секунду..."), false);
            CasinoCity.build(w);
        }
        RETURN.put(p.getUuid(), Saved.of(p));
        p.teleport(w, 0.5, 5, 8.5, 180f, 0f);
        if (VISITED.add(p.getUuid())) {
            pay(p, 16);
            save();
            p.sendMessage(Text.literal("Добро пожаловать в казино! Стартовый бонус: 16 фишек. Домой: /casino home"), false);
        }
        return true;
    }

    public static void toHome(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        if (JAILED.containsKey(p.getUuid())) {
            p.sendMessage(Text.literal("Ты в долговой яме. Сначала выкупись: /casino bail " + p.getName().getString()), false);
            return;
        }
        if (!p.getServerWorld().getRegistryKey().equals(CASINO)) {
            p.sendMessage(Text.literal("Ты не в казино."), false);
            return;
        }
        Saved back = RETURN.remove(p.getUuid());
        if (back != null) {
            back.tp(s, p);
        } else {
            ServerWorld ow = s.getOverworld();
            net.minecraft.util.math.BlockPos sp = ow.getSpawnPos();
            p.teleport(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, p.getYaw(), p.getPitch());
        }
    }

    // ---------- яма ----------
    public static void setPit(ServerWorld w, net.minecraft.util.math.BlockPos pos) {
        pit = new Saved(w.getRegistryKey(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        save();
    }

    private static void release(MinecraftServer server, ServerPlayerEntity t) {
        Saved back = JAILED.remove(t.getUuid());
        save();
        if (back != null) back.tp(server, t);
    }

    private static void jail(MinecraftServer server, ServerPlayerEntity target) {
        if (pit == null || JAILED.containsKey(target.getUuid())
                || server.getPlayerManager().getPlayer(target.getUuid()) == null) return;
        JAILED.put(target.getUuid(), Saved.of(target));
        save();
        pit.tp(server, target);
        target.sendMessage(Text.literal("Тебя продали в долговую яму! Выкуп: /casino bail " + target.getName().getString()
                + " (" + BAIL_CHIPS + " фишек) — или пусть друзья скинутся."), false);
    }

    // ---------- сохранение ----------
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dep_state.txt"); }

    private static void save() {
        List<String> lines = new ArrayList<>();
        if (pit != null) lines.add("P " + pit.ser());
        for (UUID u : OPTOUT) lines.add("O " + u);
        for (UUID u : VISITED) lines.add("V " + u);
        for (Map.Entry<UUID, Saved> e : JAILED.entrySet()) lines.add("J " + e.getKey() + " " + e.getValue().ser());
        try { Files.write(file(), lines); } catch (IOException ex) { ex.printStackTrace(); }
    }

    private static void load() {
        pit = null; JAILED.clear(); OPTOUT.clear(); VISITED.clear();
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] t = line.trim().split(" ");
                if (t[0].equals("P") && t.length == 5) pit = Saved.parse(t, 1);
                else if (t[0].equals("O") && t.length == 2) OPTOUT.add(UUID.fromString(t[1]));
                else if (t[0].equals("V") && t.length == 2) VISITED.add(UUID.fromString(t[1]));
                else if (t[0].equals("J") && t.length == 6) JAILED.put(UUID.fromString(t[1]), Saved.parse(t, 2));
            }
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    // ---------- фишки ----------
    private static int count(PlayerEntity p, Item item) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    private static boolean take(PlayerEntity p, Item item, int need) {
        if (count(p, item) < need) return false;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size() && need > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item)) {
                int d = Math.min(s.getCount(), need);
                s.decrement(d);
                need -= d;
            }
        }
        return true;
    }

    private static void pay(ServerPlayerEntity p, int chips) {
        if (chips > 0) p.getInventory().offerOrDrop(new ItemStack(CHIP, chips));
    }

    // ---------- ставки ----------
    /** Возвращает друга-ставку или null, если ставка недействительна (тогда ставка снята). */
    private static ServerPlayerEntity validTarget(ServerPlayerEntity p) {
        UUID tid = STAKES.get(p.getUuid());
        ServerPlayerEntity t = p.getServer().getPlayerManager().getPlayer(tid);
        if (t == null || JAILED.containsKey(tid)) {
            STAKES.remove(p.getUuid());
            p.sendMessage(Text.literal("Друга нет онлайн или он уже в яме. Ставка снята."), false);
            return null;
        }
        if (pit == null) {
            p.sendMessage(Text.literal("Нет долговой ямы: поставь блок «Долговая яма»."), false);
            return null;
        }
        return t;
    }

    private static void sound(ServerPlayerEntity p, SoundEvent s) {
        p.getServerWorld().playSound(null, p.getBlockPos(), s, SoundCategory.BLOCKS, 1f, 1f);
    }

    private static void broadcast(MinecraftServer server, String msg) {
        server.getPlayerManager().broadcast(Text.literal(msg), false);
    }

    private static void say(ServerPlayerEntity p, ServerPlayerEntity friend, String msg) {
        if (friend != null) broadcast(p.getServer(), msg);
        else p.sendMessage(Text.literal(msg), false);
    }

    // ---------- защита города ----------
    private static void registerProtection() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) ->
                !(world.getRegistryKey().equals(CASINO) && !player.isCreative()));

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.getRegistryKey().equals(CASINO) || player.isCreative()) return ActionResult.PASS;
            ItemStack held = player.getStackInHand(hand);
            if (held.isEmpty() || held.isOf(TICKET) || held.isOf(CHIP)) return ActionResult.PASS;
            Block clicked = world.getBlockState(hit.getBlockPos()).getBlock();
            if (clicked == SLOT_MACHINE || clicked == ROULETTE || clicked == DEBT_PIT) return ActionResult.PASS;
            return ActionResult.FAIL; // нельзя ставить блоки и лить воду в казино
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!entity.getCommandTags().contains("dep_croupier")) return ActionResult.PASS;
            if (!world.isClient && hand == Hand.MAIN_HAND && player instanceof ServerPlayerEntity sp) {
                blackjackStart(sp);
            }
            return ActionResult.SUCCESS;
        });
    }

    // ---------- планировщик ----------
    private static final class Sched {
        int left;
        final Runnable run;
        Sched(int left, Runnable run) { this.left = left; this.run = run; }
    }
    private static final List<Sched> TASKS = new ArrayList<>();
    private static final Set<UUID> BUSY = new HashSet<>();

    public static void later(int ticks, Runnable r) { TASKS.add(new Sched(ticks, r)); }

    private static void runTasks() {
        if (TASKS.isEmpty()) return;
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Sched> it = TASKS.iterator(); it.hasNext();) {
            Sched t = it.next();
            if (--t.left <= 0) { due.add(t.run); it.remove(); }
        }
        for (Runnable r : due) {
            try { r.run(); } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private static ServerPlayerEntity online(MinecraftServer s, UUID id) {
        return s.getPlayerManager().getPlayer(id);
    }

    private static void tick(ServerPlayerEntity pl, float pitch) {
        pl.getServerWorld().playSound(null, pl.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.6f, pitch);
    }

    // ---------- слоты (с анимацией барабанов) ----------
    private static final String[] SYMBOLS = {"Вишня", "Лимон", "Семёрка", "Алмаз", "Колокол"};

    private static void animSlot(ServerPlayerEntity p, int a, int b, int c, Consumer<ServerPlayerEntity> done) {
        MinecraftServer s = p.getServer();
        UUID id = p.getUuid();
        BUSY.add(id);
        final int frames = 14;
        for (int k = 0; k <= frames; k++) {
            final int kk = k;
            later(k * 2, () -> {
                ServerPlayerEntity pl = online(s, id);
                if (pl == null) { BUSY.remove(id); return; }
                int r1 = kk < 6 ? pl.getRandom().nextInt(5) : a;   // барабаны останавливаются по очереди
                int r2 = kk < 10 ? pl.getRandom().nextInt(5) : b;
                int r3 = kk < 14 ? pl.getRandom().nextInt(5) : c;
                pl.sendMessage(Text.literal("[ " + SYMBOLS[r1] + " | " + SYMBOLS[r2] + " | " + SYMBOLS[r3] + " ]"), true);
                tick(pl, 0.8f + 0.05f * kk);
                if (kk == frames) { BUSY.remove(id); done.accept(pl); }
            });
        }
    }

    public static void spinSlot(ServerPlayerEntity p) {
        if (BUSY.contains(p.getUuid())) { p.sendMessage(Text.literal("Барабаны ещё крутятся..."), true); return; }
        UUID friendId = null;
        if (STAKES.containsKey(p.getUuid())) {
            ServerPlayerEntity f = validTarget(p);
            if (f == null) return;
            friendId = f.getUuid();
            STAKES.remove(p.getUuid());
        } else if (!take(p, CHIP, 1)) {
            p.sendMessage(Text.literal("Нужна 1 фишка (1 золотой слиток = 4 фишки) или поставь друга: /dep <ник>"), true);
            return;
        }
        final UUID fid = friendId;
        int a = p.getRandom().nextInt(5), b = p.getRandom().nextInt(5), c = p.getRandom().nextInt(5);
        animSlot(p, a, b, c, pl -> slotPay(pl, fid, a, b, c));
    }

    private static void slotPay(ServerPlayerEntity pl, UUID fid, int a, int b, int c) {
        boolean fm = fid != null;
        ServerPlayerEntity friend = fm ? online(pl.getServer(), fid) : null;
        String me = pl.getName().getString();
        String fr = friend == null ? "друг" : friend.getName().getString();
        String line = "[ " + SYMBOLS[a] + " | " + SYMBOLS[b] + " | " + SYMBOLS[c] + " ]";
        boolean three = a == b && b == c;
        boolean pair = !three && (a == b || b == c || a == c);

        if (three) {
            pay(pl, SLOT_JACKPOT);
            sound(pl, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
            say(pl, friend, line + "  ДЖЕКПОТ! " + me + " берёт " + SLOT_JACKPOT + " фишек" + (fm ? ", " + fr + " спасён." : "."));
        } else if (pair) {
            pay(pl, fm ? 0 : 1);
            sound(pl, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);
            say(pl, friend, line + "  Пара. " + (fm ? me + " вернул ставку, " + fr + " спасён." : "Фишка возвращена."));
        } else if (!fm) {
            sound(pl, SoundEvents.BLOCK_ANVIL_LAND);
            say(pl, null, line + "  Мимо. Фишка ушла казино.");
        } else if (friend == null) {
            pl.sendMessage(Text.literal(line + "  Проигрыш, но друг уже вышел из игры. Ставка сгорела."), false);
        } else {
            sound(pl, SoundEvents.ENTITY_WITHER_SPAWN);
            broadcast(pl.getServer(), line + "  " + me + " проиграл " + fr + " в казино! Тот отправляется в долговую яму.");
            jail(pl.getServer(), friend);
        }
    }

    // ---------- рулетка (с анимацией) ----------
    // ПКМ — ставка на красное, ПКМ с шифтом — на чёрное. Зеро забирает казино.
    private static Text rouletteText(int n) {
        String c = n == 0 ? "ЗЕРО" : (n >= 19 ? "ЧЁРНОЕ" : "КРАСНОЕ");
        Formatting f = n == 0 ? Formatting.GREEN : (n >= 19 ? Formatting.GRAY : Formatting.RED);
        return Text.literal("Рулетка: " + n + " " + c).formatted(f);
    }

    public static void spinRoulette(ServerPlayerEntity p) {
        if (BUSY.contains(p.getUuid())) { p.sendMessage(Text.literal("Шарик ещё катится..."), true); return; }
        UUID friendId = null;
        if (STAKES.containsKey(p.getUuid())) {
            ServerPlayerEntity f = validTarget(p);
            if (f == null) return;
            friendId = f.getUuid();
            STAKES.remove(p.getUuid());
        } else if (!take(p, CHIP, 1)) {
            p.sendMessage(Text.literal("Нужна 1 фишка или поставь друга: /dep <ник>. ПКМ — красное, шифт+ПКМ — чёрное."), true);
            return;
        }
        final UUID fid = friendId;
        final int n = p.getRandom().nextInt(37);
        final boolean betBlack = p.isSneaking();
        MinecraftServer s = p.getServer();
        UUID id = p.getUuid();
        BUSY.add(id);
        final int frames = 16;
        for (int k = 0; k <= frames; k++) {
            final int kk = k;
            int at = k < 10 ? 2 * k : 20 + (k - 10) * 4; // шарик замедляется
            later(at, () -> {
                ServerPlayerEntity pl = online(s, id);
                if (pl == null) { BUSY.remove(id); return; }
                int shown = kk == frames ? n : pl.getRandom().nextInt(37);
                pl.sendMessage(rouletteText(shown), true);
                tick(pl, 1.0f + 0.03f * kk);
                if (kk == frames) { BUSY.remove(id); roulettePay(pl, fid, n, betBlack); }
            });
        }
    }

    private static void roulettePay(ServerPlayerEntity pl, UUID fid, int n, boolean betBlack) {
        boolean fm = fid != null;
        ServerPlayerEntity friend = fm ? online(pl.getServer(), fid) : null;
        String me = pl.getName().getString();
        String fr = friend == null ? "друг" : friend.getName().getString();
        boolean black = n >= 19;
        boolean win = n != 0 && black == betBlack;
        String color = n == 0 ? "ЗЕРО" : (black ? "ЧЁРНОЕ" : "КРАСНОЕ");
        String head = "Рулетка: " + n + " " + color + " (ставка на " + (betBlack ? "чёрное" : "красное") + ").  ";

        if (win) {
            pay(pl, fm ? 3 : 2);
            sound(pl, SoundEvents.ENTITY_PLAYER_LEVELUP);
            say(pl, friend, head + me + " выиграл" + (fm ? ", " + fr + " спасён, плюс 3 фишки." : " 2 фишки."));
        } else if (!fm) {
            sound(pl, SoundEvents.BLOCK_ANVIL_LAND);
            say(pl, null, head + "Проигрыш.");
        } else if (friend == null) {
            pl.sendMessage(Text.literal(head + "Проигрыш, но друг уже вышел из игры. Ставка сгорела."), false);
        } else {
            sound(pl, SoundEvents.ENTITY_WITHER_SPAWN);
            broadcast(pl.getServer(), head + me + " проиграл " + fr + "! Долговая яма ждёт.");
            jail(pl.getServer(), friend);
        }
    }

    // ---------- блэкджек ----------
    // ПКМ по крупье. Ставка 1 фишка или друг (/dep). Выигрыш 2 фишки, блэкджек 3, ничья — ставка назад.
    private static final class BJ {
        UUID friend;
        final List<Integer> me = new ArrayList<>();
        final List<Integer> dealer = new ArrayList<>();
    }
    private static final Map<UUID, BJ> GAMES = new HashMap<>();

    private static int draw(ServerPlayerEntity p) { return p.getRandom().nextInt(13) + 1; }

    private static String card(int r) {
        return r == 1 ? "A" : r <= 10 ? String.valueOf(r) : r == 11 ? "J" : r == 12 ? "Q" : "K";
    }

    private static int total(List<Integer> h) {
        int t = 0;
        boolean ace = false;
        for (int r : h) { t += Math.min(r, 10); if (r == 1) ace = true; }
        return (ace && t + 10 <= 21) ? t + 10 : t;
    }

    private static String show(List<Integer> h) {
        StringBuilder sb = new StringBuilder();
        for (int r : h) sb.append("[").append(card(r)).append("] ");
        return sb.toString().trim();
    }

    private static Text button(String label, String cmd, Formatting f) {
        return Text.literal(label).styled(st -> st.withColor(f).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)));
    }

    public static void blackjackStart(ServerPlayerEntity p) {
        BJ g = GAMES.get(p.getUuid());
        if (g != null) { bjShow(p, g); return; }
        UUID friendId = null;
        if (STAKES.containsKey(p.getUuid())) {
            ServerPlayerEntity f = validTarget(p);
            if (f == null) return;
            friendId = f.getUuid();
            STAKES.remove(p.getUuid());
        } else if (!take(p, CHIP, 1)) {
            p.sendMessage(Text.literal("Блэкджек: нужна 1 фишка или ставка другом (/dep <ник>)."), true);
            return;
        }
        g = new BJ();
        g.friend = friendId;
        g.me.add(draw(p)); g.dealer.add(draw(p));
        g.me.add(draw(p)); g.dealer.add(draw(p));
        GAMES.put(p.getUuid(), g);
        if (total(g.me) == 21) { bjEnd(p, g); return; }
        bjShow(p, g);
    }

    private static void bjShow(ServerPlayerEntity p, BJ g) {
        Text t = Text.literal("Блэкджек. Крупье: [" + card(g.dealer.get(0)) + "] [?]   Ты: " + show(g.me)
                        + " (" + total(g.me) + ")   ")
                .append(button("[Взять]", "/blackjack hit", Formatting.GREEN))
                .append(Text.literal(" "))
                .append(button("[Хватит]", "/blackjack stand", Formatting.GOLD));
        p.sendMessage(t, false);
        tick(p, 1.2f);
    }

    public static void bjHit(ServerPlayerEntity p) {
        BJ g = GAMES.get(p.getUuid());
        if (g == null) { p.sendMessage(Text.literal("Нет активной игры. ПКМ по крупье."), true); return; }
        g.me.add(draw(p));
        int t = total(g.me);
        if (t > 21) bjEnd(p, g);
        else if (t == 21) bjStand(p);
        else bjShow(p, g);
    }

    public static void bjStand(ServerPlayerEntity p) {
        BJ g = GAMES.get(p.getUuid());
        if (g == null) { p.sendMessage(Text.literal("Нет активной игры. ПКМ по крупье."), true); return; }
        while (total(g.dealer) < 17) g.dealer.add(draw(p));
        bjEnd(p, g);
    }

    private static void bjEnd(ServerPlayerEntity p, BJ g) {
        GAMES.remove(p.getUuid());
        int pt = total(g.me), dt = total(g.dealer);
        boolean natural = g.me.size() == 2 && pt == 21;
        boolean dealerNatural = g.dealer.size() == 2 && dt == 21;
        int outcome; // 0 проигрыш, 1 ничья, 2 победа, 3 блэкджек
        if (pt > 21) outcome = 0;
        else if (natural) outcome = dealerNatural ? 1 : 3;
        else if (dealerNatural) outcome = 0;
        else if (dt > 21 || pt > dt) outcome = 2;
        else if (pt == dt) outcome = 1;
        else outcome = 0;

        boolean fm = g.friend != null;
        ServerPlayerEntity friend = fm ? online(p.getServer(), g.friend) : null;
        String me = p.getName().getString();
        String fr = friend == null ? "друг" : friend.getName().getString();
        String head = "Блэкджек: крупье " + show(g.dealer) + " (" + dt + "), " + me + " " + show(g.me) + " (" + pt + ").  ";

        switch (outcome) {
            case 3 -> {
                pay(p, fm ? 4 : 3);
                sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
                say(p, friend, head + "БЛЭКДЖЕК!" + (fm ? " " + fr + " спасён, плюс 4 фишки." : " 3 фишки."));
            }
            case 2 -> {
                pay(p, 2);
                sound(p, SoundEvents.ENTITY_PLAYER_LEVELUP);
                say(p, friend, head + "Победа!" + (fm ? " " + fr + " спасён, плюс 2 фишки." : " 2 фишки."));
            }
            case 1 -> {
                pay(p, fm ? 0 : 1);
                sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);
                say(p, friend, head + "Ничья. " + (fm ? fr + " спасён." : "Фишка возвращена."));
            }
            default -> {
                if (!fm) {
                    sound(p, SoundEvents.BLOCK_ANVIL_LAND);
                    say(p, null, head + "Проигрыш.");
                } else if (friend == null) {
                    p.sendMessage(Text.literal(head + "Проигрыш, но друг уже вышел из игры. Ставка сгорела."), false);
                } else {
                    sound(p, SoundEvents.ENTITY_WITHER_SPAWN);
                    broadcast(p.getServer(), head + me + " проиграл " + fr + "! Долговая яма ждёт.");
                    jail(p.getServer(), friend);
                }
            }
        }
    }
}
