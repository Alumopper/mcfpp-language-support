# Type after @, @e[, a command argument, or a resource ID to exercise DPS completion.
say MCFPP IDEA datapack support is active
execute as @e[type=minecraft:zombie,distance=..16,limit=1] at @s run say Selector completion works
setblock ~ ~-1 ~ minecraft:gold_block

$setblock ~ ~ ~ $(a)