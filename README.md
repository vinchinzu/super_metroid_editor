# Super Metroid Editor (SMEDIT)

A native macOS (and cross-platform) Super Metroid ROM editor built with Kotlin Multiplatform and Compose Multiplatform.

## Features (v1)

- ✅ Load Super Metroid `.smc` ROM files
- ✅ Display list of all rooms with IDs and names
- ✅ View room information
- 🚧 Render room maps (coming soon)

## Tech Stack

- **Kotlin Multiplatform** - Shared business logic
- **Compose Multiplatform** - Native UI framework
- **Kotlinx Serialization** - JSON parsing for room data

## Project Structure

```
super_metroid_dev/
├── shared/                    # Shared Kotlin code
│   ├── data/                 # Data models and repositories
│   └── rom/                  # ROM parsing logic
├── desktopApp/               # Desktop application (macOS/Windows/Linux)
│   └── ui/                   # Compose UI components
└── resources/                # Room mapping data
```

## Building

### Prerequisites

- JDK 17 or higher
- Gradle 8.5+

### Build Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew :desktopApp:run

# Create a distributable
./gradlew :desktopApp:packageDmg  # macOS
./gradlew :desktopApp:packageMsi   # Windows
./gradlew :desktopApp:packageDeb   # Linux
```

## Room Data

Room IDs and names are loaded from `shared/src/commonMain/resources/room_mapping_complete.json`, which contains all 140+ rooms from Super Metroid with their:
- Room IDs (hex format, e.g., `0X91F8`)
- Handles (internal identifiers)
- Names (display names)
- Comments (optional)

## ROM Format

The editor supports Super Metroid ROM files in `.smc` format:
- **Size**: 3,145,728 bytes (3MB) or 3,146,240 bytes (3MB + 512 byte header)
- **Header**: Optional 512-byte header (SMC format)
- **Room Headers**: Located at SNES address `0x8F0000`
- **Room Header Size**: 38 bytes per room

## Future Features

- [ ] Full room map rendering with tiles
- [ ] Tile editor
- [ ] Door configuration editor
- [ ] Item placement editor
- [ ] Room state editor
- [ ] Save ROM modifications

## References

- [Metroid Construction Wiki](https://wiki.metroidconstruction.com/)
- [Super Metroid Room Data Format](https://wiki.metroidconstruction.com/doku.php?id=sm:technical_information:room_data_format)
- [SMILE Editor](https://wiki.metroidconstruction.com/doku.php?id=sm:editor_utility_guides:smile2.5) (Windows reference)

## License

This project is for educational purposes and Super Metroid ROM hacking.
