// Ruta: Server/factory/DefaultFactory.java
package Server.factory;

import Server.entities.*;

public class DefaultFactory implements GameElementFactory {
    @Override
    public Enemy createCrocodile(String type, int liana, int y) {
        if ("RED".equalsIgnoreCase(type)) return new RedCroc(liana, y);
        return new BlueCroc(liana, y);
    }

    @Override
    public Fruit createFruit(int liana, int y, int points) {
        return new SimpleFruit(liana, y, points);
    }
}

