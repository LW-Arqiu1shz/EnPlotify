package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import io.github.lwarqiu1shz.enplotify.paper.PrinterBlockEntity;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Renames a printer (Alt + left click). An empty box means the default "打印机[x,y,z]". */
final class PrinterNameScreen extends Screen {
    private final BlockPos pos;
    private final String current;
    private final String defaultName;
    /** Where the new name goes (printer or terminal). */
    private final java.util.function.Consumer<String> send;
    private EditBox box;

    PrinterNameScreen(BlockPos pos, String current, String defaultName) {
        this(Component.translatable("enplotify.printer.rename"), current, defaultName,
                text -> ClientPacketDistributor.sendToServer(new PaperNetwork.PrinterRename(pos.immutable(), text)), pos);
    }

    /** Renaming a terminal (Alt + left click). */
    static PrinterNameScreen terminal(BlockPos pos, String current, String defaultName) {
        return new PrinterNameScreen(Component.translatable("enplotify.terminal.rename"), current, defaultName,
                text -> ClientPacketDistributor.sendToServer(new PaperNetwork.TerminalRename(pos.immutable(), text)), pos);
    }

    /** Renaming a display (Alt + left click). */
    static PrinterNameScreen display(int entityId, String current, String defaultName) {
        return new PrinterNameScreen(Component.translatable("enplotify.display.rename"), current, defaultName,
                text -> ClientPacketDistributor.sendToServer(new PaperNetwork.DisplayRename(entityId, text)), BlockPos.ZERO);
    }

    private PrinterNameScreen(Component title, String current, String defaultName, java.util.function.Consumer<String> send, BlockPos pos) {
        super(title);
        this.pos = pos.immutable();
        this.current = current;
        this.defaultName = defaultName;
        this.send = send;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 10;
        box = new EditBox(this.font, cx - 100, y, 200, 20, this.title);
        box.setMaxLength(PrinterBlockEntity.MAX_NAME);
        box.setValue(current);
        box.setHint(Component.literal(defaultName));
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done())
                .bounds(cx - 102, y + 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y + 28, 100, 20).build());
    }

    private void done() {
        String text = box.getValue().strip();
        if (text.equals(defaultName)) text = "";
        if (!text.equals(current)) send.accept(text);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            done();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
