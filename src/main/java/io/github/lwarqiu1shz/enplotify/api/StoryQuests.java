package io.github.lwarqiu1shz.enplotify.api;

import io.github.lwarqiu1shz.enplotify.quest.QuestProgress;
import io.github.lwarqiu1shz.enplotify.quest.QuestSync;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The story quest map (opened with J by default): each story is a {@link Line} of stages, every
 * stage with the requirements that finish it and so unlock the next one.
 *
 * <h2>Defining a line (once, e.g. in your mod's constructor; common code)</h2>
 * <pre>
 *   StoryQuests.Line wake = StoryQuests.line(Identifier.fromNamespaceAndPath("mymod", "awakening"),
 *           Component.translatable("quest.mymod.awakening"));
 *   wake.stage("observatory", Component.translatable("quest.mymod.observatory"))
 *           .requirement("dig", Component.translatable("quest.mymod.observatory.dig"))       // done / not done
 *           .requirement("logs", Component.translatable("quest.mymod.observatory.logs"), 3); // counted, 0/3
 *   wake.stage("spark", Component.translatable("quest.mymod.spark"));   // comes after "observatory"
 * </pre>
 * A stage follows the stage defined before it unless {@link Stage#after} says otherwise
 * ({@code after()} with nothing = a starting stage; several ids = it unlocks when any one of them is done).
 * {@link Stage#root} makes a root: shown in bold, finished as soon as it unlocks; a line may have
 * several roots, each with its own chain of stages after it.
 *
 * <h2>Progress (server side)</h2>
 * {@link #start} unlocks the line's starting stages and shows the line on the map. A stage
 * finishes when all its requirements are met (or with {@link #complete}); the stages that only
 * waited for it unlock by themselves. Progress is saved with the player and survives death.
 * {@link QuestEvent} reports unlocked and finished stages.
 * <p>
 * {@link Line#shared()} makes a line's progress the whole server's: everyone is as far as the
 * furthest player (see {@link #shareMet} to hand in progress kept elsewhere).
 */
public final class StoryQuests {
    private static final Map<Identifier, Line> LINES = Collections.synchronizedMap(new LinkedHashMap<>());

    /** Defines (or returns the already defined) line {@code id}. */
    public static Line line(Identifier id, Component title) {
        synchronized (LINES) {
            return LINES.computeIfAbsent(id, key -> new Line(key, title));
        }
    }

    /** The defined line, or null. */
    public static Line get(Identifier id) {
        return LINES.get(id);
    }

    /** Every defined line, in the order they were defined. */
    public static List<Line> lines() {
        synchronized (LINES) {
            return List.copyOf(LINES.values());
        }
    }

    // ---- progress ----

    /** Shows the line on the player's map and unlocks its starting stages. */
    public static void start(ServerPlayer player, Identifier line) {
        Line l = require(line);
        for (Stage stage : l.stages()) if (l.parents(stage).isEmpty()) unlock(player, line, stage.id);
        focusFirstOpen(player, l);
    }

    /**
     * Brings a player's started lines up to date with their definitions (roots finished, stages
     * whose parent is done unlocked). Runs by itself when the player logs in.
     */
    public static void refresh(ServerPlayer player) {
        QuestProgress p = QuestProgress.of(player);
        for (Line l : started(player)) {
            String before = p.current;
            for (Stage stage : l.stages()) {
                if (l.parents(stage).isEmpty()) {
                    if (stage.root) unlock(player, l.id, stage.id);
                    continue;
                }
                for (String parent : l.parents(stage)) {
                    if (p.done.contains(QuestProgress.key(l.id, parent))) {
                        unlock(player, l.id, stage.id);
                        break;
                    }
                }
            }
            if (!before.equals(p.current)) focusFirstOpen(player, l);
        }
    }

    /** The line's first stage in progress becomes the current task (several roots unlock at once). */
    private static void focusFirstOpen(ServerPlayer player, Line l) {
        QuestProgress p = QuestProgress.of(player);
        for (Stage stage : l.stages()) {
            String key = QuestProgress.key(l.id, stage.id);
            if (p.unlocked.contains(key)) {
                if (!key.equals(p.current)) {
                    p.current = key;
                    changed(player);
                }
                return;
            }
        }
    }

    /** Unlocks the stage (it becomes the current one), whatever came before it. */
    public static void unlock(ServerPlayer player, Identifier line, String stage) {
        Stage s = require(line).require(stage);
        QuestProgress p = QuestProgress.of(player);
        String key = QuestProgress.key(line, stage);
        if (p.done.contains(key) || !p.unlocked.add(key)) return;
        p.current = key;
        changed(player);
        NeoForge.EVENT_BUS.post(new QuestEvent.StageUnlocked(player, line, stage));
        if (s.root) complete(player, line, stage);
        else checkFinished(player, line, s);
    }

    /** Finishes the stage (all its requirements count as met) and unlocks the stages after it. */
    public static void complete(ServerPlayer player, Identifier line, String stage) {
        Line l = require(line);
        Stage s = l.require(stage);
        QuestProgress p = QuestProgress.of(player);
        String key = QuestProgress.key(line, stage);
        if (p.done.contains(key)) return;
        p.unlocked.remove(key);
        p.done.add(key);
        for (Requirement r : s.requirements) p.counts.put(QuestProgress.key(line, stage, r.id), r.target);
        changed(player);
        NeoForge.EVENT_BUS.post(new QuestEvent.StageCompleted(player, line, stage));
        // Any one finished parent unlocks a stage.
        for (Stage next : l.stages()) {
            if (l.parents(next).contains(stage)) unlock(player, line, next.id);
        }
    }

    /** Sets how far a counted requirement is (a done / not done one: 0 or 1). */
    public static void setProgress(ServerPlayer player, Identifier line, String stage, String requirement, int value) {
        Stage s = require(line).require(stage);
        Requirement r = s.requireRequirement(requirement);
        QuestProgress p = QuestProgress.of(player);
        if (p.done.contains(QuestProgress.key(line, stage))) return;
        int clamped = Math.max(0, Math.min(r.target, value));
        Integer old = p.counts.put(QuestProgress.key(line, stage, requirement), clamped);
        if (old != null && old == clamped) return;
        changed(player);
        checkFinished(player, line, s);
    }

    /** Adds to a counted requirement. */
    public static void addProgress(ServerPlayer player, Identifier line, String stage, String requirement, int amount) {
        setProgress(player, line, stage, requirement, progress(player, line, stage, requirement) + amount);
    }

    /** Marks a requirement as met. */
    public static void meet(ServerPlayer player, Identifier line, String stage, String requirement) {
        setProgress(player, line, stage, requirement, require(line).require(stage).requireRequirement(requirement).target);
    }

    /** Forgets the player's progress in the line (it leaves the map until started again). */
    public static void reset(ServerPlayer player, Identifier line) {
        QuestProgress p = QuestProgress.of(player);
        String prefix = line + "#";
        p.unlocked.removeIf(k -> k.startsWith(prefix));
        p.done.removeIf(k -> k.startsWith(prefix));
        p.counts.keySet().removeIf(k -> k.startsWith(prefix));
        if (p.current.startsWith(prefix)) p.current = "";
        changed(player);
    }

    public static boolean isUnlocked(ServerPlayer player, Identifier line, String stage) {
        return QuestProgress.of(player).unlocked.contains(QuestProgress.key(line, stage));
    }

    public static boolean isDone(ServerPlayer player, Identifier line, String stage) {
        return QuestProgress.of(player).done.contains(QuestProgress.key(line, stage));
    }

    public static int progress(ServerPlayer player, Identifier line, String stage, String requirement) {
        return QuestProgress.of(player).counts.getOrDefault(QuestProgress.key(line, stage, requirement), 0);
    }

    private static void checkFinished(ServerPlayer player, Identifier line, Stage stage) {
        if (stage.root || stage.requirements.isEmpty() || !isUnlocked(player, line, stage.id)) return;
        for (Requirement r : stage.requirements) {
            if (progress(player, line, stage.id, r.id) < r.target) return;
        }
        complete(player, line, stage.id);
    }

    private static void changed(ServerPlayer player) {
        QuestProgress.save(player);
        QuestSync.markDirty(player);
    }

    private static Line require(Identifier id) {
        Line line = LINES.get(id);
        if (line == null) throw new IllegalArgumentException("EnPlotify: no quest line " + id);
        return line;
    }

    /** Marks a requirement of a {@link Line#shared() shared} line as met for the whole server. */
    public static void shareMet(net.minecraft.server.MinecraftServer server, Identifier line, String stage, String requirement) {
        io.github.lwarqiu1shz.enplotify.quest.SharedQuests.shareMet(server, line, stage, requirement);
    }

    // ---- definitions ----

    /** A story's stages, in the order they were defined. */
    public static final class Line {
        public final Identifier id;
        public final Component title;
        private final List<Stage> stages = new ArrayList<>();
        private volatile boolean shared;

        private Line(Identifier id, Component title) {
            this.id = id;
            this.title = title;
        }

        /**
         * Shares the line's progress across the server: every player, players who join later
         * included, is as far as the furthest one (saved with the world). What a player reached is
         * passed on only once nobody online is still in a story that player watched, and players in
         * a story get it when theirs is over.
         */
        public Line shared() {
            this.shared = true;
            return this;
        }

        public boolean isShared() {
            return shared;
        }

        /** Adds a stage; it comes after the stage defined before it (see {@link Stage#after}). */
        public synchronized Stage stage(String id, Component title) {
            for (Stage s : stages) if (s.id.equals(id)) return s;
            Stage stage = new Stage(id, title, stages.isEmpty() ? null : stages.get(stages.size() - 1).id);
            stages.add(stage);
            return stage;
        }

        public synchronized List<Stage> stages() {
            return List.copyOf(stages);
        }

        /** The stages that must be finished before {@code stage} unlocks. */
        public List<String> parents(Stage stage) {
            return stage.after != null ? stage.after : stage.previous == null ? List.of() : List.of(stage.previous);
        }

        public synchronized Stage get(String id) {
            for (Stage s : stages) if (s.id.equals(id)) return s;
            return null;
        }

        private Stage require(String id) {
            Stage stage = get(id);
            if (stage == null) throw new IllegalArgumentException("EnPlotify: no stage " + id + " in quest line " + this.id);
            return stage;
        }
    }

    /** One stage of a line. */
    public static final class Stage {
        public final String id;
        public final Component title;
        private final String previous;
        private List<String> after;
        private Component description;
        private boolean secret;
        private boolean root;
        private boolean branches;
        private final List<Requirement> requirements = new ArrayList<>();

        private Stage(String id, Component title, String previous) {
            this.id = id;
            this.title = title;
            this.previous = previous;
        }

        /** The stages before this one: it unlocks when any one of them is done (none = a starting stage). */
        public Stage after(String... stages) {
            this.after = List.of(stages);
            return this;
        }

        /** A line or two shown under the title on the map. */
        public Stage description(Component description) {
            this.description = description;
            return this;
        }

        /**
         * Makes this a root: a starting stage (nothing before it) shown in bold, finished the moment
         * it unlocks, so the stages after it open at once. Requirements are ignored; no banner.
         */
        public Stage root() {
            this.root = true;
            this.after = List.of();
            return this;
        }

        public boolean isRoot() {
            return root;
        }

        /**
         * Shows this stage's requirements on the map as branch nodes the player can open and fold,
         * and gives each finished requirement a small banner. Other stages list them on the side only.
         */
        public Stage branches() {
            this.branches = true;
            return this;
        }

        public boolean hasBranches() {
            return branches;
        }

        /** Shows "???" instead of the title until it unlocks. */
        public Stage secret() {
            this.secret = true;
            return this;
        }

        /** A requirement that is either met or not. */
        public Stage requirement(String id, Component text) {
            return requirement(id, text, 1);
        }

        /** A counted requirement: met at {@code target}. */
        public Stage requirement(String id, Component text, int target) {
            return requirement(id, text, target, null);
        }

        /** A requirement with a description, shown when its branch node is picked on the map. */
        public Stage requirement(String id, Component text, Component description) {
            return requirement(id, text, 1, description);
        }

        /** A counted requirement with a description. */
        public Stage requirement(String id, Component text, int target, Component description) {
            requirements.removeIf(r -> r.id.equals(id));
            requirements.add(new Requirement(id, text, Math.max(1, target), description));
            return this;
        }

        public Component description() {
            return description;
        }

        public boolean isSecret() {
            return secret;
        }

        public List<Requirement> requirements() {
            return List.copyOf(requirements);
        }

        private Requirement requireRequirement(String id) {
            for (Requirement r : requirements) if (r.id.equals(id)) return r;
            throw new IllegalArgumentException("EnPlotify: no requirement " + id + " in stage " + this.id);
        }
    }

    /** What finishes a stage; {@code target} 1 = done / not done; {@code description} may be null. */
    public record Requirement(String id, Component text, int target, Component description) {
        public Requirement(String id, Component text, int target) {
            this(id, text, target, null);
        }
    }

    /** Every line the player has started (for the map). */
    public static Collection<Line> started(ServerPlayer player) {
        QuestProgress p = QuestProgress.of(player);
        List<Line> out = new ArrayList<>();
        for (Line line : lines()) {
            String prefix = line.id + "#";
            boolean any = false;
            for (String k : p.unlocked) any |= k.startsWith(prefix);
            for (String k : p.done) any |= k.startsWith(prefix);
            if (any) out.add(line);
        }
        return out;
    }

    private StoryQuests() {}
}
