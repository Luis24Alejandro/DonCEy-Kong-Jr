// Ruta: Server/Player.java
package Server;

public class Player {
    public final int id;
    public final String name;
    public int x, y;
    public int lastAckSeq; // último INPUT seq procesado para este jugador

    public Player(int id, String name) {
        this.id = id;
        this.name = name;
        this.x = 0;
        this.y = 0;
        this.lastAckSeq = 0;
    }
}
