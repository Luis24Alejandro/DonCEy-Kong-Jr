// Ruta: Server/entities/SimpleFruit.java
package Server.entities;

public class SimpleFruit implements Fruit {
    private final int liana, y, points;

    public SimpleFruit(int liana, int y, int points) {
        this.liana = liana;
        this.y = y;
        this.points = points;
    }

    @Override public int getX() { return liana; }
    @Override public int getY() { return y; }
    @Override public int getPoints() { return points; }
}

