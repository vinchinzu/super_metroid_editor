-- BizHawk TCP bridge for Super Metroid Editor.
-- Load with: EmuHawk --lua=bridge.lua
-- Connects the editor (Kotlin/JVM) to BizHawk via JSON-per-line over TCP.

local json = require("json")

local PORT = tonumber(rawget(_G, "SMEDIT_BIZHAWK_PORT")) or 43884
local BIND_ADDRESS = "127.0.0.1"
local PATH_SEPARATOR = package.config:sub(1, 1)
local IS_WINDOWS = PATH_SEPARATOR == "\\"

-- Super Metroid WRAM addresses (LoROM bank $7E).
local ADDR_ROOM_ID    = 0x079B  -- 2 bytes, current room pointer
local ADDR_GAME_STATE = 0x0998  -- 2 bytes
local ADDR_SAMUS_X    = 0x0AF6  -- 2 bytes, Samus X position
local ADDR_SAMUS_Y    = 0x0AFA  -- 2 bytes, Samus Y position
local ADDR_HEALTH     = 0x09C2  -- 2 bytes, current energy
local ADDR_DOOR_TRANSITION = 0x0797 -- 2 bytes

-- SNES button order matching the 12-element array convention:
-- B, Y, Select, Start, Up, Down, Left, Right, A, X, L, R
local BUTTON_NAMES = {"B", "Y", "Select", "Start", "Up", "Down", "Left", "Right", "A", "X", "L", "R"}

-- State --
local server = nil
local client_socket = nil
local session_active = false
local trace = {}
local frame_counter = 0

-- Helpers --

local function log(msg)
    print("[bridge] " .. msg)
end

local function env_var(name)
    local injected = rawget(_G, name)
    if injected ~= nil and tostring(injected) ~= "" then
        return tostring(injected)
    end
    if os and os.getenv then
        local from_env = os.getenv(name)
        if from_env ~= nil and from_env ~= "" then
            return from_env
        end
    end
    return nil
end

local function read_word(addr)
    return memory.read_u16_le(addr, "WRAM")
end

local function join_path(dir, leaf)
    if dir:sub(-1) == PATH_SEPARATOR then
        return dir .. leaf
    end
    return dir .. PATH_SEPARATOR .. leaf
end

local function dirname(path)
    if not path or #path == 0 then
        return nil
    end
    local normalized = path:gsub("[/\\]+$", "")
    local last_sep = normalized:match("^.*()[/\\]")
    if not last_sep then
        return "."
    end
    return normalized:sub(1, last_sep - 1)
end

local function shell_quote(value)
    value = tostring(value)
    if IS_WINDOWS then
        return '"' .. value:gsub('"', '""') .. '"'
    end
    return "'" .. value:gsub("'", "'\"'\"'") .. "'"
end

local function ensure_directory(path)
    if IS_WINDOWS then
        os.execute("if not exist " .. shell_quote(path) .. " mkdir " .. shell_quote(path))
    else
        os.execute("mkdir -p " .. shell_quote(path))
    end
end

local function get_snapshot(include_frame)
    local room_id = read_word(ADDR_ROOM_ID)
    local game_state = read_word(ADDR_GAME_STATE)
    local samus_x = read_word(ADDR_SAMUS_X)
    local samus_y = read_word(ADDR_SAMUS_Y)
    local health = read_word(ADDR_HEALTH)
    local door_raw = read_word(ADDR_DOOR_TRANSITION)
    local door_transition = (door_raw ~= 0)

    -- Record trace point
    trace[#trace + 1] = {
        frame = frame_counter,
        roomId = room_id,
        x = samus_x,
        y = samus_y,
    }
    -- Keep last 2000 trace points
    if #trace > 2000 then
        local new_trace = {}
        for i = #trace - 1999, #trace do
            new_trace[#new_trace + 1] = trace[i]
        end
        trace = new_trace
    end

    local snap = {
        frameCounter = frame_counter,
        roomId = room_id,
        gameState = game_state,
        health = health,
        samusX = samus_x,
        samusY = samus_y,
        doorTransition = door_transition,
        trace = trace,
    }

    if include_frame then
        local w = client.screenwidth()
        local h = client.screenheight()
        local screenshot = client.screenshot()
        if screenshot and #screenshot > 0 then
            -- BizHawk client.screenshot() returns a base64 PNG.
            -- We pass it through; the editor can decode PNG as well as raw RGB.
            snap.frameWidth = w
            snap.frameHeight = h
            snap.frameRgb24Base64 = screenshot
        end
    end

    return snap
end

local function get_session_state(state_name)
    return {
        active = session_active,
        paused = not session_active,
        currentState = state_name,
        frameCounter = frame_counter,
    }
end

local function ensure_save_dir(preferred_base_dir)
    local explicit_dir = env_var("SMEDIT_STATE_DIR")
    if explicit_dir and #explicit_dir > 0 then
        ensure_directory(explicit_dir)
        return explicit_dir
    end
    local base_dir = preferred_base_dir
    if not base_dir or #base_dir == 0 then
        base_dir = emu.getdir("rom")
    end
    if not base_dir or #base_dir == 0 then
        base_dir = env_var("SMEDIT_BIZHAWK_WORKDIR") or "."
    end
    local save_dir = join_path(base_dir, "editor_states")
    ensure_directory(save_dir)
    return save_dir
end

local function list_states()
    local dir = ensure_save_dir()
    local states = {}
    local command
    if IS_WINDOWS then
        command = "dir /b /a-d " .. shell_quote(join_path(dir, "*.state")) .. " 2>nul"
    else
        command = "ls -1 " .. shell_quote(dir) .. "/*.state 2>/dev/null"
    end
    local handle = io.popen(command)
    if handle then
        for line in handle:lines() do
            local path = line
            if IS_WINDOWS and not line:match("^[A-Za-z]:") and line:sub(1, 1) ~= "\\" then
                path = join_path(dir, line)
            end
            local name = path:match("([^/]+)%.state$")
                or path:match("([^\\]+)%.state$")
            if name then
                states[#states + 1] = { name = name, path = path }
            end
        end
        handle:close()
    end
    return states
end

local function apply_buttons(buttons)
    if not buttons or #buttons == 0 then return end
    local input = {}
    for i, name in ipairs(BUTTON_NAMES) do
        if buttons[i] and buttons[i] ~= 0 then
            input[name] = true
        end
    end
    joypad.set(input, 1)
end

-- Command handlers --

local function handle_hello()
    return {
        ok = true,
        message = "BizHawk bridge connected",
        capabilities = {
            emulator = "BizHawk",
            supportsFrames = true,
            supportsMemoryAccess = true,
            supportsSaveStates = true,
        },
    }
end

local function handle_load_rom(cmd)
    if cmd.romPath then
        client.openrom(cmd.romPath)
    end
    if cmd.stateName then
        local dir = ensure_save_dir(dirname(cmd.romPath))
        local path = join_path(dir, cmd.stateName .. ".state")
        if io.open(path, "r") then
            savestate.load(path)
        end
    end
    session_active = true
    frame_counter = emu.framecount()
    trace = {}
    return {
        ok = true,
        message = "ROM loaded",
        session = get_session_state(cmd.stateName),
        snapshot = get_snapshot(true),
    }
end

local function handle_step(cmd)
    local repeats = cmd["repeat"] or 1
    local include_frame = cmd.includeFrame
    if include_frame == nil then include_frame = true end

    for i = 1, repeats do
        apply_buttons(cmd.buttons)
        emu.frameadvance()
        frame_counter = emu.framecount()
    end

    return {
        ok = true,
        session = get_session_state(nil),
        snapshot = get_snapshot(include_frame),
    }
end

local function handle_snapshot(cmd)
    local include_frame = cmd.includeFrame
    if include_frame == nil then include_frame = true end
    return {
        ok = true,
        snapshot = get_snapshot(include_frame),
    }
end

local function handle_close_session()
    session_active = false
    joypad.set({}, 1)
    return {
        ok = true,
        message = "Session closed",
        session = get_session_state(nil),
        states = list_states(),
    }
end

local function handle_save_state(cmd)
    local name = cmd.stateName or "quicksave"
    local dir = ensure_save_dir()
    local path = join_path(dir, name .. ".state")
    savestate.save(path)
    return {
        ok = true,
        message = "State saved: " .. name,
        states = list_states(),
    }
end

local function handle_load_state(cmd)
    local name = cmd.stateName
    if not name then
        return { ok = false, error = "stateName required" }
    end
    local dir = ensure_save_dir()
    local path = join_path(dir, name .. ".state")
    local f = io.open(path, "r")
    if not f then
        return { ok = false, error = "State not found: " .. name }
    end
    f:close()
    savestate.load(path)
    frame_counter = emu.framecount()
    trace = {}
    return {
        ok = true,
        message = "State loaded: " .. name,
        session = get_session_state(name),
        snapshot = get_snapshot(true),
    }
end

local function handle_list_states()
    return {
        ok = true,
        states = list_states(),
    }
end

local function handle_read_memory(cmd)
    local addr = cmd.address
    local size = cmd.size or 1
    if not addr then
        return { ok = false, error = "address required" }
    end
    local data = {}
    for i = 0, size - 1 do
        data[#data + 1] = memory.read_u8(addr + i, "WRAM")
    end
    return {
        ok = true,
        memoryData = data,
    }
end

local function handle_write_memory(cmd)
    local addr = cmd.address
    local data = cmd.data
    if not addr or not data then
        return { ok = false, error = "address and data required" }
    end
    for i, byte in ipairs(data) do
        memory.write_u8(addr + (i - 1), byte, "WRAM")
    end
    return {
        ok = true,
        message = "Wrote " .. #data .. " bytes at 0x" .. string.format("%04X", addr),
    }
end

local HANDLERS = {
    hello = handle_hello,
    load_rom = handle_load_rom,
    step = handle_step,
    snapshot = handle_snapshot,
    close_session = handle_close_session,
    save_state = handle_save_state,
    load_state = handle_load_state,
    list_states = handle_list_states,
    read_memory = handle_read_memory,
    write_memory = handle_write_memory,
}

-- TCP server --

local function setup_server()
    local socket = require("socket")
    server = socket.bind(BIND_ADDRESS, PORT)
    server:settimeout(0) -- non-blocking
    log("Listening on " .. BIND_ADDRESS .. ":" .. PORT)
end

local function accept_client()
    if client_socket then return end
    local c = server:accept()
    if c then
        c:settimeout(0)
        client_socket = c
        log("Client connected")
    end
end

local function process_commands()
    if not client_socket then return end

    local line, err = client_socket:receive("*l")
    if err == "timeout" then
        return
    elseif err then
        log("Client disconnected: " .. tostring(err))
        client_socket:close()
        client_socket = nil
        return
    end

    if not line or #line == 0 then return end

    local ok_parse, cmd = pcall(json.decode, line)
    if not ok_parse or type(cmd) ~= "table" then
        local resp = json.encode({ ok = false, error = "Invalid JSON" })
        client_socket:send(resp .. "\n")
        return
    end

    local command = cmd.command
    local handler = HANDLERS[command]
    local response
    if handler then
        local ok_exec, result = pcall(handler, cmd)
        if ok_exec then
            response = result
        else
            response = { ok = false, error = "Handler error: " .. tostring(result) }
        end
    else
        response = { ok = false, error = "Unknown command: " .. tostring(command) }
    end

    local resp_json = json.encode(response)
    client_socket:send(resp_json .. "\n")
end

-- Main loop --

local function on_frame()
    accept_client()
    process_commands()
end

-- Initialize
log("Starting BizHawk bridge...")
setup_server()

-- Register frame callback
event.onframeend(on_frame)

log("Bridge ready. Waiting for editor connection...")
