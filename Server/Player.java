// Server/Player.java
package Server;

public class Player {
    public final int id;
    public final String name;
    public int x, y;
    public int lastAckSeq;
    public int score;
    public int round;
    public boolean gameOver;   

    public Player(int id, String name) {
        this.id = id;
        this.name = name;
        this.x = 0;
        this.y = 0;
        this.lastAckSeq = 0;
        this.score = 0;
        this.round = 1;
        this.gameOver = false; //
    }
}

