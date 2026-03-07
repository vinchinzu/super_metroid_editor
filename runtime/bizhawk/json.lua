-- Minimal JSON encoder/decoder for BizHawk Lua bridge.
-- Handles strings, numbers, booleans, nil/null, arrays, and objects.

local json = {}

-- Encoding --

local function encode_string(s)
    s = s:gsub('\\', '\\\\')
    s = s:gsub('"', '\\"')
    s = s:gsub('\n', '\\n')
    s = s:gsub('\r', '\\r')
    s = s:gsub('\t', '\\t')
    return '"' .. s .. '"'
end

local encode_value -- forward declaration

local function encode_array(arr)
    local parts = {}
    for i = 1, #arr do
        parts[i] = encode_value(arr[i])
    end
    return '[' .. table.concat(parts, ',') .. ']'
end

local function encode_object(obj)
    local parts = {}
    for k, v in pairs(obj) do
        parts[#parts + 1] = encode_string(tostring(k)) .. ':' .. encode_value(v)
    end
    return '{' .. table.concat(parts, ',') .. '}'
end

local function is_array(t)
    if type(t) ~= 'table' then return false end
    local n = #t
    if n == 0 then
        -- empty table: check if it has any keys at all
        return next(t) == nil
    end
    for k in pairs(t) do
        if type(k) ~= 'number' or k < 1 or k > n or k ~= math.floor(k) then
            return false
        end
    end
    return true
end

encode_value = function(v)
    if v == nil then
        return 'null'
    elseif type(v) == 'boolean' then
        return v and 'true' or 'false'
    elseif type(v) == 'number' then
        if v ~= v then return 'null' end -- NaN
        if v == math.huge or v == -math.huge then return 'null' end
        if v == math.floor(v) and math.abs(v) < 2^53 then
            return string.format('%d', v)
        end
        return tostring(v)
    elseif type(v) == 'string' then
        return encode_string(v)
    elseif type(v) == 'table' then
        if is_array(v) then
            return encode_array(v)
        else
            return encode_object(v)
        end
    else
        return 'null'
    end
end

function json.encode(value)
    return encode_value(value)
end

-- Decoding --

local decode_value -- forward declaration

local function skip_whitespace(str, pos)
    while pos <= #str do
        local c = str:byte(pos)
        if c == 32 or c == 9 or c == 10 or c == 13 then
            pos = pos + 1
        else
            break
        end
    end
    return pos
end

local function decode_string(str, pos)
    -- pos should be at the opening quote
    pos = pos + 1
    local parts = {}
    while pos <= #str do
        local c = str:sub(pos, pos)
        if c == '"' then
            return table.concat(parts), pos + 1
        elseif c == '\\' then
            pos = pos + 1
            local esc = str:sub(pos, pos)
            if esc == '"' then parts[#parts + 1] = '"'
            elseif esc == '\\' then parts[#parts + 1] = '\\'
            elseif esc == '/' then parts[#parts + 1] = '/'
            elseif esc == 'n' then parts[#parts + 1] = '\n'
            elseif esc == 'r' then parts[#parts + 1] = '\r'
            elseif esc == 't' then parts[#parts + 1] = '\t'
            elseif esc == 'u' then
                -- Basic unicode escape (ASCII range only)
                local hex = str:sub(pos + 1, pos + 4)
                local code = tonumber(hex, 16) or 63
                parts[#parts + 1] = string.char(code)
                pos = pos + 4
            end
            pos = pos + 1
        else
            parts[#parts + 1] = c
            pos = pos + 1
        end
    end
    error('Unterminated string')
end

local function decode_number(str, pos)
    local start = pos
    if str:sub(pos, pos) == '-' then pos = pos + 1 end
    while pos <= #str and str:byte(pos) >= 48 and str:byte(pos) <= 57 do
        pos = pos + 1
    end
    if pos <= #str and str:sub(pos, pos) == '.' then
        pos = pos + 1
        while pos <= #str and str:byte(pos) >= 48 and str:byte(pos) <= 57 do
            pos = pos + 1
        end
    end
    if pos <= #str and (str:sub(pos, pos) == 'e' or str:sub(pos, pos) == 'E') then
        pos = pos + 1
        if pos <= #str and (str:sub(pos, pos) == '+' or str:sub(pos, pos) == '-') then
            pos = pos + 1
        end
        while pos <= #str and str:byte(pos) >= 48 and str:byte(pos) <= 57 do
            pos = pos + 1
        end
    end
    return tonumber(str:sub(start, pos - 1)), pos
end

local function decode_array(str, pos)
    pos = pos + 1 -- skip '['
    pos = skip_whitespace(str, pos)
    local arr = {}
    if str:sub(pos, pos) == ']' then
        return arr, pos + 1
    end
    while true do
        local val
        val, pos = decode_value(str, pos)
        arr[#arr + 1] = val
        pos = skip_whitespace(str, pos)
        local c = str:sub(pos, pos)
        if c == ']' then
            return arr, pos + 1
        elseif c == ',' then
            pos = skip_whitespace(str, pos + 1)
        else
            error('Expected , or ] in array at position ' .. pos)
        end
    end
end

local function decode_object(str, pos)
    pos = pos + 1 -- skip '{'
    pos = skip_whitespace(str, pos)
    local obj = {}
    if str:sub(pos, pos) == '}' then
        return obj, pos + 1
    end
    while true do
        if str:sub(pos, pos) ~= '"' then
            error('Expected string key at position ' .. pos)
        end
        local key
        key, pos = decode_string(str, pos)
        pos = skip_whitespace(str, pos)
        if str:sub(pos, pos) ~= ':' then
            error('Expected : at position ' .. pos)
        end
        pos = skip_whitespace(str, pos + 1)
        local val
        val, pos = decode_value(str, pos)
        obj[key] = val
        pos = skip_whitespace(str, pos)
        local c = str:sub(pos, pos)
        if c == '}' then
            return obj, pos + 1
        elseif c == ',' then
            pos = skip_whitespace(str, pos + 1)
        else
            error('Expected , or } in object at position ' .. pos)
        end
    end
end

decode_value = function(str, pos)
    pos = skip_whitespace(str, pos)
    local c = str:sub(pos, pos)
    if c == '"' then
        return decode_string(str, pos)
    elseif c == '{' then
        return decode_object(str, pos)
    elseif c == '[' then
        return decode_array(str, pos)
    elseif c == 't' then
        if str:sub(pos, pos + 3) == 'true' then
            return true, pos + 4
        end
        error('Invalid value at position ' .. pos)
    elseif c == 'f' then
        if str:sub(pos, pos + 4) == 'false' then
            return false, pos + 5
        end
        error('Invalid value at position ' .. pos)
    elseif c == 'n' then
        if str:sub(pos, pos + 3) == 'null' then
            return nil, pos + 4
        end
        error('Invalid value at position ' .. pos)
    elseif c == '-' or (c >= '0' and c <= '9') then
        return decode_number(str, pos)
    else
        error('Unexpected character at position ' .. pos .. ': ' .. c)
    end
end

function json.decode(str)
    local val, pos = decode_value(str, 1)
    return val
end

return json
