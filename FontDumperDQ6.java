import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

public class FontDumperDQ6 {

    private static final int LOOKUP_TABLE_START = 0x159D2;
    private static final int PTR_TO_FONT_BANK_NUM = 0x03310;
    private static final int STRUCT_SIZE = 5;
    private static final int NUM_CHAR_GROUPS = 51;
    private static final int MAX_DIMEN = 0x10;

    // -------------------------------------------------------------------------

    private static int widthTable[] = new int[NUM_CHAR_GROUPS];
    private static int heightTable[] = new int[NUM_CHAR_GROUPS];
    private static int groupSizeTable[] = new int[NUM_CHAR_GROUPS];
    private static int offsetTable[] = new int[NUM_CHAR_GROUPS];

    private static RandomAccessFile romStream;
    private static FileOutputStream rawFontDump;
    private static FileOutputStream tiledFontDump;

    // -------------------------------------------------------------------------

    private static void readLookupTable() throws IOException {
        romStream = new RandomAccessFile("Dragon Quest VI - Maboroshi no Daichi (Japan).sfc", "r");

        // get the bank number for the font (should be 0x1F)
        // turn it into a "ROM file" base offset, as in not a LoROM CPU offset
        romStream.seek(PTR_TO_FONT_BANK_NUM);
        int fontBank = romStream.readUnsignedByte();
        int fontBankOffset = 0x10000 * (fontBank & 0x3F);

        int totalCharsSoFar = 1;
        romStream.seek(LOOKUP_TABLE_START);
        for (int i = 0; i < NUM_CHAR_GROUPS; i++) {
            // format of 5-byte lookup table entry:
            // 00000000 11111111 22222222 33333333 44444444
            // ssssssss WWWWssss [bank 1F  offset] 00HHHH00

            int data[] = new int[STRUCT_SIZE];
            for (int j = 0; j < STRUCT_SIZE; j++) {
                data[j] = romStream.readUnsignedByte();
            }

            groupSizeTable[i] = (data[0] | (data[1] << 8)) & 0xFFF;
            widthTable[i]     = data[1] >> 4;
            heightTable[i]    = data[4] >> 2;
            offsetTable[i]    = fontBankOffset + (data[2] | (data[3] << 8));

            int lastCharInGroup = totalCharsSoFar + groupSizeTable[i] - 1;
            String info = "Group %2d has %3X %X*%X chars (%3X-%3X) at 0x%6X\n";
            System.out.printf(info, i + 1, groupSizeTable[i], widthTable[i], heightTable[i], totalCharsSoFar, lastCharInGroup, offsetTable[i]);

            totalCharsSoFar += groupSizeTable[i];
        }
    }

    private static void dumpFontGroup(int groupNum) throws IOException {
        int groupSize = groupSizeTable[groupNum];
        int height = heightTable[groupNum];
        int width = widthTable[groupNum];
        int groupOffset = offsetTable[groupNum];

        // get a 16-bit bitmask with the top (width) bits set
        // e.g. W=1 -> 8000, W=7 -> FE00, W=A -> FFC0
        int bitmask = (0xFFFF << (16 - width)) & 0xFFFF;
        int bitPos = 0;

        // dump the font data for each character in the group
        for (int chNum = 0; chNum < groupSize; chNum++) {
            // buffer for the pixel data of one character
            int pixelRows[] = new int[MAX_DIMEN];

            // DQ VI measures char heights from the bottom up
            // get the top row to write pixel data into
            int topRow = pixelRows.length - height;

            for (int r = 0; r < height; r++) {
                // a row of up to 15 pixels can extend across 3 bytes
                int bytePos = bitPos >> 3;
                romStream.seek(groupOffset + bytePos);
                int rawPixelData = romStream.readUnsignedByte() |
                                   (romStream.readUnsignedByte() << 8) |
                                   (romStream.readUnsignedByte() << 16);

                // determine if need to align pixels left/right, and by how much
                // - align so leftmost pixel is on the MSB of a two byte value
                int alignment = (bitPos & 0x7) + width - MAX_DIMEN;
                if (alignment > 0) {
                    rawPixelData >>= alignment;
                }
                else if (alignment < 0) {
                    rawPixelData <<= Math.abs(alignment);
                }

                pixelRows[topRow + r] = rawPixelData & bitmask;
                bitPos += width;
            }

            outputPixelData(pixelRows);
            outputTileData(pixelRows);
        }
    }

    private static void outputPixelData(int pixelRows[]) throws IOException {
        // the raw pixel data is viewable in YY-CHR with format "1bpp 16x16"
        for (int r = 0; r < pixelRows.length; r++) {
            rawFontDump.write(pixelRows[r] >> 8);
            rawFontDump.write(pixelRows[r] & 0xFF);
        }
    }

    private static void outputTileData(int pixelRows[]) throws IOException {
        // split up font data across four 8x8 tiles (TL, TR, BL, BR)
        // intent is for this to be compatible with more tile editors

        // instead of storing as 16 two-byte values, store as 32 one-byte values
        // specifically as four groups of 8 bytes: [TL ; TR ; BL ; BR]
        final int TILE_SIZE = MAX_DIMEN / 2;
        int tileData[] = new int[MAX_DIMEN * 2];
        for (int i = 0; i < pixelRows.length; i++) {
            // first 16 bytes = top two tiles; last 16 bytes = bottom two tiles
            int topBottom = (i & TILE_SIZE) * 2;
            int tileRow = i & 0x7;

            // write a row of 8 pixels for left tile (left half of row)
            tileData[tileRow + topBottom] = pixelRows[i] >> TILE_SIZE;

            // same for right tile (right half of row)
            // write data at the correct array position
            tileData[tileRow + topBottom + TILE_SIZE] = pixelRows[i] & 0xFF;
        }

        for (int i = 0; i < tileData.length; i++) {
            tiledFontDump.write(tileData[i]);
        }
    }

    public static void main(String args[]) throws IOException {
        readLookupTable();
        rawFontDump = new FileOutputStream("JP font dump 16x16 raw.bin");
        tiledFontDump = new FileOutputStream("JP font dump 8x8 tiles.bin");

        for (int i = 0; i < 0x20; i++) {
            rawFontDump.write(0x00);
            tiledFontDump.write(0x00);
        }

        for (int i = 0; i < NUM_CHAR_GROUPS; i++) {
            dumpFontGroup(i);
        }
        rawFontDump.close();
    }
}
