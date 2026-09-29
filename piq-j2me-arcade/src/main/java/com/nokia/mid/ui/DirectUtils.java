package com.nokia.mid.ui;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public abstract class DirectUtils {
    protected DirectUtils() {
    }

    public static DirectGraphics getDirectGraphics(Graphics graphics) {
        return new DirectGraphicsImplemented(graphics);
    }

    public static Image createImage(byte[] imageData, int imageOffset, int imageLength) {
        return Image.createImage(imageData, imageOffset, imageLength);
    }

    public static Image createImage(int width, int height, int argbColor) {
        Image image = Image.createImage(width, height);
        Graphics graphics = image.getGraphics();
        graphics.setColor(argbColor & 0x00ffffff);
        graphics.fillRect(0, 0, width, height);
        return image;
    }
}
