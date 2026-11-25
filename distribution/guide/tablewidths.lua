-- Column widths for the reading copies of the install guide (ODT/PDF).
--
-- Markdown pipe tables whose separator row has equal dashes come out with equal columns, which
-- squeezes a long "Description" column into a quarter of the page. Give each column a share of
-- the width proportional to its longest cell, bounded so that no column is starved or takes all.

local function cell_text(cell)
  return pandoc.utils.stringify(cell.contents)
end

-- The longest run without a space: a column narrower than that breaks words in the middle.
local function longest_word(text)
  local best = 0
  for word in text:gmatch("%S+") do best = math.max(best, #word) end
  return best
end

function Table(tbl)
  local n = #tbl.colspecs
  local longest, word = {}, {}
  for i = 1, n do longest[i] = 4; word[i] = 4 end

  local function scan(rows)
    for _, row in ipairs(rows) do
      for i, cell in ipairs(row.cells) do
        if i <= n then
          local text = cell_text(cell)
          longest[i] = math.max(longest[i], #text)
          word[i] = math.max(word[i], longest_word(text))
        end
      end
    end
  end
  scan(tbl.head.rows)
  for _, body in ipairs(tbl.bodies) do scan(body.body) end

  local weights, total = {}, 0
  for i = 1, n do
    weights[i] = math.max(math.min(longest[i], 70), math.floor(word[i] * 1.3)) + 8
    total = total + weights[i]
  end
  for i = 1, n do
    tbl.colspecs[i] = { tbl.colspecs[i][1], weights[i] / total }
  end
  return tbl
end
