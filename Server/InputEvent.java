package Server;

public class InputEvent {
    public final int playerId;
    public final int seq;
    public final int dx;
    public final int dy;

    public InputEvent(int playerId, int seq, int dx, int dy) {
        this.playerId = playerId;
        this.seq = seq;
        this.dx = dx;
        this.dy = dy;
    }
    
}
