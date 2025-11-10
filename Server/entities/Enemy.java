// Ruta: Server/entities/Enemy.java
package Server.entities;

public interface Enemy {
    void tick(int minY, int maxY);
    int getX();
    int getY();
    String getType(); // "RED" | "BLUE"
}
