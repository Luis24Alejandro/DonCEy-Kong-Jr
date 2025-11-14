#ifndef GAME_MAP_H
#define GAME_MAP_H

#define MAP_W 11
#define MAP_H 11

// Fila 0 = y=0, fila 10 = y=10
static const char *MAP_ROWS[MAP_H] = {
    "....G......", // y=10 (arriba)
    "....|......",
    "..===......",
    "..|........",
    "..|....===.",
    "..|....|...",
    "..|....|...",
    "..===..|...",
    "......=|...",
    "S.....=|...",
    "......=...."  // y=0 (abajo)
};

static inline char MapGetTile(int x, int y) {
    if (x < 0 || x >= MAP_W || y < 0 || y >= MAP_H) return '.';
    return MAP_ROWS[y][x];
}

#endif // GAME_MAP_H

