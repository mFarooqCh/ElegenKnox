# Data Model

## Entities

### UnlockInputState (in-memory only)

- rawInput: String (as entered)
- normalizedInput: String (trimmed for comparison)
- isEmpty: Boolean
- isMatch: Boolean
- messageType: Enum (SUCCESS, ERROR, EMPTY)

## Persistence

No persistence; the state lives only in memory while the activity is running.
