package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.EnPlotifyConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Native NeoForge config screen; number options are text boxes with their own reset button. */
final class EnPlotifyConfigScreen {
    private EnPlotifyConfigScreen() {}

    static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, parent) -> new ConfigurationScreen(mod, parent,
                        (root, type, config, title) -> new Section(root, type, config, title)));
    }

    private static final class Section extends ConfigurationScreen.ConfigurationSectionScreen {
        private final ModConfig.Type type;

        private Section(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
            super(parent, type, config, title);
            this.type = type;
        }

        /** The client settings also get the quest map key (when some mod uses the quest map). */
        @Override
        protected java.util.Collection<? extends Element> createSyntheticValues() {
            if (type != ModConfig.Type.CLIENT || QuestClient.KEY == null) return super.createSyntheticValues();
            java.util.List<Element> all = new java.util.ArrayList<>(super.createSyntheticValues());
            all.add(new Element(Component.translatable("config.enplotify.quest_key"),
                    Component.translatable("config.enplotify.quest_key.tooltip"), new KeyWithReset(QuestClient.KEY)));
            return all;
        }

        @Override
        protected Element createDoubleValue(String key, ModConfigSpec.ValueSpec spec,
                                            Supplier<Double> source, Consumer<Double> target) {
            Predicate<Double> tester;
            if (key.endsWith("text_size")) {
                tester = value -> value != null && EnPlotifyConfig.isValidTextSize(value);
            } else if (key.endsWith("text_speed")) {
                tester = value -> value != null && EnPlotifyConfig.isValidTextSpeed(value);
            } else {
                tester = null;
            }
            Element base = createNumberBox(key, spec, source, target, tester, Double::parseDouble, 0.0);
            if (base == null || !(base.widget() instanceof EditBox box)) return base;
            // NeoForge drops text it can't read as a number yet; allow every half-typed decimal.
            box.setFilter(text -> text.matches("-?\\d*\\.?\\d*"));
            Object def = spec.getDefault();
            String defaultText = def instanceof Double d && d == Math.rint(d)
                    ? String.valueOf(d.longValue()) : String.valueOf(def);
            if (box.getValue().endsWith(".0")) box.setValue(box.getValue().substring(0, box.getValue().length() - 2));
            return new Element(base.name(), base.tooltip(), new NumberWithReset(box, defaultText));
        }

        /** The picture size: a button that opens the list of all sizes (a drop-down list), not a cycle button. */
        @Override
        protected <T extends Enum<T>> Element createEnumValue(String key, ModConfigSpec.ValueSpec spec, Supplier<T> source, Consumer<T> target) {
            if (!key.endsWith("screenshot_resolution")) return super.createEnumValue(key, spec, source, target);
            @SuppressWarnings("unchecked") Class<T> clazz = (Class<T>) spec.getClazz();
            if (clazz == null) return super.createEnumValue(key, spec, source, target);
            java.util.List<T> list = java.util.Arrays.stream(clazz.getEnumConstants()).filter(spec::test).toList();
            Button[] button = new Button[1];
            button[0] = Button.builder(label(source.get()), b -> this.minecraft.setScreen(new ChoiceScreen<>(this, getTranslationComponent(key),
                            list, source.get(), chosen -> {
                                T before = source.get();
                                if (chosen == before) return;
                                undoManager.add(v -> {
                                    target.accept(v);
                                    onChanged(key);
                                }, chosen, v -> {
                                    target.accept(v);
                                    onChanged(key);
                                }, before);
                                button[0].setMessage(label(chosen));
                            })))
                    .width(Button.DEFAULT_WIDTH).build();
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), button[0]);
        }

        private static Component label(Enum<?> value) {
            Component name = value instanceof net.neoforged.neoforge.common.TranslatableEnum t ? t.getTranslatedName() : Component.literal(value.name());
            return Component.empty().append(name).append(" ▼");
        }
    }

    /** A list of choices over the options screen; picking one (or Esc) goes back. */
    private static final class ChoiceScreen<T extends Enum<T>> extends Screen {
        private final Screen parent;
        private final java.util.List<T> choices;
        private final T current;
        private final Consumer<T> chosen;

        private ChoiceScreen(Screen parent, Component title, java.util.List<T> choices, T current, Consumer<T> chosen) {
            super(title);
            this.parent = parent;
            this.choices = choices;
            this.current = current;
            this.chosen = chosen;
        }

        @Override
        protected void init() {
            int columns = this.height < choices.size() * 22 + 60 ? 2 : 1;
            int rows = (choices.size() + columns - 1) / columns;
            int w = 120, h = 20, gap = 2;
            int left = this.width / 2 - (columns * w + (columns - 1) * 6) / 2;
            int top = Math.max(30, this.height / 2 - rows * (h + gap) / 2);
            for (int i = 0; i < choices.size(); i++) {
                T value = choices.get(i);
                Component name = value instanceof net.neoforged.neoforge.common.TranslatableEnum t ? t.getTranslatedName() : Component.literal(value.name());
                if (value == current) name = Component.literal("✔ ").append(name);
                int x = left + (i / rows) * (w + 6), y = top + (i % rows) * (h + gap);
                addRenderableWidget(Button.builder(name, b -> {
                    chosen.accept(value);
                    onClose();
                }).bounds(x, y, w, h).build());
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            super.extractRenderState(graphics, mouseX, mouseY, a);
            graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            this.minecraft.setScreen(parent);
        }
    }

    /**
     * A key binding: click, then press a key (Esc keeps the old one); the button on the right puts
     * back the default. Saved with the game's own key settings, the same as in Controls.
     */
    private static final class KeyWithReset extends AbstractWidget {
        private static final int RESET_WIDTH = 40;

        private final net.minecraft.client.KeyMapping key;
        private final Button bind;
        private final Button reset;
        private boolean listening;

        private KeyWithReset(net.minecraft.client.KeyMapping key) {
            super(0, 0, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, Component.empty());
            this.key = key;
            this.bind = Button.builder(Component.empty(), b -> listening = !listening)
                    .width(Button.DEFAULT_WIDTH - RESET_WIDTH - 4).build();
            this.reset = Button.builder(Component.translatable("config.enplotify.reset_value"), b -> {
                        listening = false;
                        set(key.getDefaultKey());
                    })
                    .width(RESET_WIDTH)
                    .tooltip(Tooltip.create(Component.translatable("config.enplotify.reset_value.tooltip",
                            key.getDefaultKey().getDisplayName())))
                    .build();
        }

        private void set(com.mojang.blaze3d.platform.InputConstants.Key to) {
            key.setKey(to);
            net.minecraft.client.KeyMapping.resetMapping();
            net.minecraft.client.Minecraft.getInstance().options.save();
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            this.bind.setX(x);
            this.reset.setX(x + Button.DEFAULT_WIDTH - RESET_WIDTH);
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            this.bind.setY(y);
            this.reset.setY(y);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            Component name = key.getTranslatedKeyMessage();
            this.bind.setMessage(listening
                    ? Component.literal("> ").append(name.copy().withStyle(net.minecraft.ChatFormatting.WHITE, net.minecraft.ChatFormatting.UNDERLINE))
                            .append(" <").withStyle(net.minecraft.ChatFormatting.YELLOW)
                    : name);
            this.reset.active = !key.isDefault();
            this.bind.extractRenderState(graphics, mouseX, mouseY, a);
            this.reset.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (this.reset.isMouseOver(event.x(), event.y())) return this.reset.mouseClicked(event, doubleClick);
            if (this.bind.isMouseOver(event.x(), event.y())) return this.bind.mouseClicked(event, doubleClick);
            listening = false;
            return false;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            this.bind.mouseReleased(event);
            return this.reset.mouseReleased(event);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!listening) return false;
            listening = false;
            if (event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) set(com.mojang.blaze3d.platform.InputConstants.getKey(event));
            return true;
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) listening = false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.bind.updateNarration(output);
        }
    }

    /** An {@link EditBox} with a dedicated "reset to default" button on its right. */
    private static final class NumberWithReset extends AbstractWidget {
        private static final int RESET_WIDTH = 40;
        private static final int GAP = 4;

        private final EditBox box;
        private final Button reset;
        private final String defaultText;

        private NumberWithReset(EditBox box, String defaultText) {
            super(0, 0, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, Component.empty());
            this.box = box;
            this.defaultText = defaultText;
            this.box.setWidth(Button.DEFAULT_WIDTH - RESET_WIDTH - GAP);
            this.reset = Button.builder(Component.translatable("config.enplotify.reset_value"),
                            button -> {
                                if (!this.box.getValue().equals(this.defaultText)) this.box.setValue(this.defaultText);
                            })
                    .width(RESET_WIDTH)
                    .tooltip(Tooltip.create(Component.translatable("config.enplotify.reset_value.tooltip", defaultText)))
                    .build();
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            this.box.setX(x);
            this.reset.setX(x + Button.DEFAULT_WIDTH - RESET_WIDTH);
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            this.box.setY(y);
            this.reset.setY(y);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            this.reset.active = !this.box.getValue().equals(this.defaultText);
            this.box.extractRenderState(graphics, mouseX, mouseY, a);
            this.reset.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (this.reset.isMouseOver(event.x(), event.y())) {
                this.box.setFocused(false);
                return this.reset.mouseClicked(event, doubleClick);
            }
            if (this.box.isMouseOver(event.x(), event.y())) {
                this.box.setFocused(true);
                return this.box.mouseClicked(event, doubleClick);
            }
            this.box.setFocused(false);
            return false;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            this.reset.mouseReleased(event);
            return this.box.mouseReleased(event);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            return this.box.mouseDragged(event, dx, dy);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return this.box.isFocused() && this.box.keyPressed(event);
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return this.box.isFocused() && this.box.charTyped(event);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            this.box.setFocused(focused);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.box.updateNarration(output);
        }
    }
}
