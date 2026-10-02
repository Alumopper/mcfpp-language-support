# Put the caret inside a selector, item/block ID or command branch and invoke completion.
say IDEA command validation is ready
execute as @e[type=minecraft:zombie,distance=..16,limit=1] at @s run say Selector completion
setblock ~ ~-1 ~ minecraft:gold_block

# Intentional command error: a scoreboard set command needs more arguments.
scoreboard players set

# Function macro editing.
$setblock ~ ~ ~ $(block)
