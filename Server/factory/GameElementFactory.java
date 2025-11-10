// Ruta: Server/factory/GameElementFactory.java
package Server.factory;

import Server.entities.Enemy;
import Server.entities.Fruit;

public interface GameElementFactory {
    Enemy createCrocodile(String type, int liana, int y); // "RED"|"BLUE"
    Fruit createFruit(int liana, int y, int points);
}
