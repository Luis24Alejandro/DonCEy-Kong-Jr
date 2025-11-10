// Ruta: Server/entities/RedCroc.java
package Server.entities;

public class RedCroc implements Enemy {
    private final int liana;
    private int y;
    private int dir = +1; // sube/baja

    public RedCroc(int liana, int y) {
        this.liana = liana;
        this.y = y;
    }

    @Override
    public void tick(int minY, int maxY) {
        y += dir;
        if (y <= minY) { y = minY; dir = +1; }
        if (y >= maxY) { y = maxY; dir = -1; }
    }

    @Override public int getX() { return liana; }
    @Override public int getY() { return y; }
    @Override public String getType() { return "RED"; }
}
