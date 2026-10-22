# Hardware

Prototype: ESP32-S3 N16R8 development board, one INMP441 I²S microphone, breadboard and jumper wires. A second microphone is a spare.

| INMP441 | ESP32-S3 |
| --- | --- |
| VDD | 3V3 (never 5V) |
| GND | GND |
| L/R | GND |
| SD | GPIO15 |
| WS | GPIO17 |
| SCK | GPIO18 |

The board's **COM** connector is used for UART flashing and computer relay. The **USB** connector is the native USB device port for the phone. This firmware exposes custom CDC audio transport to EchoCraft, not a generic USB Audio Class microphone for every camera app.

Factory backups, device logs and personal test recordings stay local and are excluded from Git.
