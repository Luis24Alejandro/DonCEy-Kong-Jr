#ifndef GAME_MAP_H
#define GAME_MAP_H

#define MAP_W 11
#define MAP_H 11

// Leyenda:
// . = vacío
// W = agua
// T = tierra/columna que sale del agua (suelo sólido)
// = = plataforma suspendida
// | = liana
// G = zona de la jaula de DK
// S = spawn

static const char *MAP_ROWS[MAP_H] = {
    // y=0  (agua + columnas que salen)
    "WWTWWTWTWWW",
    // y=1  (suelo sobre las columnas)
    "S==..=T..==",
    // y=2
    "..|.......=",
    // y=3
    "..|..===..=",
    // y=4
    "..|..|.....",
    // y=5
    "..|..|..===",
    // y=6
    "..|..|.....",
    // y=7
    "..===|.....",
    // y=8
    ".....|..===",
    // y=9
    ".....|.....",
    // y=10 (plataforma superior con DK)
    "..GGG===..."
};

// Acceso:
static inline char MapGetTile(int x, int y) {
    if (x < 0 || x >= MAP_W || y < 0 || y >= MAP_H) return '.';
    return MAP_ROWS[y][x];
}

#endif // GAME_MAP_H


