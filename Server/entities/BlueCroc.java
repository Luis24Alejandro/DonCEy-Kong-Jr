// Ruta: Server/entities/BlueCroc.java
package Server.entities;

public class BlueCroc implements Enemy {
    private final int liana;
    private int y;

    public BlueCroc(int liana, int y) {
        this.liana = liana;
        this.y = y;
    }

    @Override
    public void tick(int minY, int maxY) {
        y++;
        if (y > maxY) y = minY; // “cae” y reaparece arriba
    }

    @Override public int getX() { return liana; }
    @Override public int getY() { return y; }
    @Override public String getType() { return "BLUE"; }
}

