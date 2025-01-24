# dq6-sfc
I randomly decided to try looking into the inner workings of Dragon Quest VI for Super Famicom in January 2025. I was surprised to find a decent amount of overlap between it and [Otogirisou](https://github.com/ButThouMust/otogirisou-english), and was able to adapt my font and script dumpers from that project for DQ VI.

These tools use the original Japanese version of the game as a base, *not* the English translation patch by NoPrgress. The specification in the [No-Intro database](https://datomatic.no-intro.org/index.php?page=show_record&s=49&n=0650) is:
```
CRC32:	33304519
MD5:	ac9955fa4c1aa8ebcc1d09511808c58b
SHA-1:	3e699dc7e064d6ac84b1981aa150fdf1672b5456
SHA-256:	90ba1868f0e75734a19f873401fab515cb945c62cd0ad173f89cf352ab86f29e
```

I don't know if I necessarily want to *work* on a translation project for this game (e.g. fix/finish NoPrgress patch, make entirely new patch), but maybe someone who does can find these tools useful.

## Included items
- Table file for the Japanese script.
  - Please contact me (issue, pull request, etc.) if I misidentified any characters.
- Some notes I made about the script font and text decompression.
- Dumper programs for any (most?) text that appears in a dedicated text box.
  - Decompress the text itself (Huffman coding) into a text file compatible with [Atlas](https://www.romhacking.net/utilities/224/).
  - Decompress the font for the text box.
  - Generate a text file with all the binary Huffman codes. More of an added bonus. I assume you're most interested in the script dumper.

## How to use
Provided that you have Java installed, open a terminal and run these commands:
```
javac *.java
java FontDumperDQ6
java DragonQuest6ScriptDumper [rom_file] [table_file]
java GenerateHuffCodes
```
Where `[rom_file]` is a legally obtained Japanese ROM with the above specification, and `[table_file]` is `dq6 jp.tbl` formatted however your terminal handles filenames with spaces in them.

## Things that are not covered
- Menu text.
- Default names for the playable characters.
- Names for items, weapons, armor, etc.
- Identification of script control codes besides "end string" and "line break".
