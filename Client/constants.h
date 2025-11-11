// Ruta: Client/constants.h
#ifndef CONSTANTS_H
#define CONSTANTS_H

// Red
#define HOST_IP            "127.0.0.1"
#define HOST_PORT          5000

// Buffers
#define LINE_MAX           4096
#define SMALL_BUF          128

// Timings (ms)
#define JOIN_PAUSE_MS      200
#define BETWEEN_INPUT_MS   150
#define BEFORE_QUIT_MS     5000   // mantener sesión viva para espectador

#endif // CONSTANTS_H
