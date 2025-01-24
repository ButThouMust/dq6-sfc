import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;

public class DragonQuest6ScriptDumper {

    private static RandomAccessFile romFile;
    private static BufferedWriter scriptOutput;

    private static final int PTR_TABLE_OFFSET = 0x15BB5;
    private static final int SCRIPT_START = 0xF7175B;
    private static final int NUM_PTRS = 870;
    private static final int NUM_STRINGS_PER_PTR = 8;
    private static final int FINAL_BLOCK_NUM = getBlockNum(NUM_PTRS - 1, 4);
    private static int scriptPtrs[];

    private static ArrayList<Integer> tableHexValues;
    private static ArrayList<String> encodings;
    private static final int NUM_ENCODINGS = 2000;
    private static final String NO_TABLE_MATCH = "N/A";

    private static void readTableFile(String tableFilename) throws IOException {
        // assume file is sorted in increasing order by character code value
        BufferedReader tableFileStream = new BufferedReader(new FileReader(tableFilename));
        tableHexValues = new ArrayList<>(NUM_ENCODINGS);
        encodings = new ArrayList<>(NUM_ENCODINGS);

        // basic format of a table file line is "[hex value]=[character]\n"
        String line;
        final String EQUALS = "=";
        while ((line = tableFileStream.readLine()) != null) {
            if (line.equals(""))
                continue;

            String split[] = line.split(EQUALS);

            // ignore special combination table entries that keep the script
            // simpler, like for ." or ?! or Mr. or Mrs.
            if (split[0].length() > 4)
                continue;

            int value = Integer.parseInt(split[0], 16);
            tableHexValues.add(value);

            // possible for a table entry to be for the equal sign "="
            if (split.length == 1) {
                encodings.add(EQUALS);
            }
            else {
                encodings.add(split[1]);
            }
        }

        System.out.println("Finished parsing table file.");
        tableFileStream.close();
        return;
    }

    private static String getEncoding(int data) {
        data &= 0x1FFF;
        int index = Collections.binarySearch(tableHexValues, data);

        String encoding = NO_TABLE_MATCH;
        if (index >= 0) {
            encoding = encodings.get(index);
        }
        return encoding;
    }

    // -------------------------------------------------------------------------
    // Data for the Huffman tree structure
    // -------------------------------------------------------------------------

    private static final int HUFF_LEFT_OFFSET = 0x167BE;
    private static final int HUFF_RIGHT_OFFSET = 0x1700E;
    private static final int HUFF_TABLE_ENTRIES = 0x428;

    private static int[] huffLeftTrees = new int[HUFF_TABLE_ENTRIES];
    private static int[] huffRightTrees = new int[HUFF_TABLE_ENTRIES];

    private static int bitOffset;
    private static int huffmanBuffer;

    // using this because I gave up trying to use RandomAccessFile's internal file
    // pointer for checking pointer targets (EMBWRITEs) and outputting "new page"
    // pointers in script; they would always be off by one
    private static int currByteOffset;

    private static void getHuffmanTreeData() throws IOException {
        romFile.seek(HUFF_LEFT_OFFSET);
        int data = 0;
        for (int i = 0; i < HUFF_TABLE_ENTRIES; i++) {
            data = romFile.readUnsignedByte();
            data |= (romFile.readUnsignedByte() << 8);
            huffLeftTrees[i] = data;
        }

        romFile.seek(HUFF_RIGHT_OFFSET);
        for (int i = 0; i < HUFF_TABLE_ENTRIES; i++) {
            data = romFile.readUnsignedByte();
            data |= (romFile.readUnsignedByte() << 8);
            huffRightTrees[i] = data;
        }
        System.out.println("Finished getting Huffman tree's contents.");
    }

    // -------------------------------------------------------------------------
    // "API" for reading raw binary data from the script
    // -------------------------------------------------------------------------

    private static int getCPUOffset(int romOffset) {
        return romOffset | 0xC00000;
    }

    // uses the independent "current byte offset"; used for navigating the script
    private static int getCPUOffsetVar() {
        return getCPUOffset(currByteOffset);
    }

    // read a byte without advancing file pointer
    private static int peekByte() throws IOException {
        int output = romFile.readUnsignedByte();
        romFile.seek(romFile.getFilePointer() - 1);
        return output;
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------

    private static void printChar(int charValue) throws IOException {
        // obtain the corresponding character from the table file
        // print to the script output text file
        String encoding = getEncoding(charValue);
        if (encoding.equals(NO_TABLE_MATCH)) {
            String format = "ERROR - character value 0x%04X @ 0x%06X-%d ($%06X-%d) not in table file!\n";
            throw new IOException(String.format(format, charValue, currByteOffset, bitOffset, getCPUOffsetVar(), bitOffset));
        }
        scriptOutput.write(encoding);
    }

    private static int readCharacter() throws IOException {
        // start going left/right from the root of the Huffman tree

        // internal representation for non-leaves is using BYTE offsets instead
        // of WORD offsets; to avoid putting in a special case, start with word
        // offset for the root of the tree
        int huffTreeValue = (HUFF_TABLE_ENTRIES - 1) << 1;
        huffTreeValue |= 0x8000;

        // Huffman leaf node (character) indicated by a CLEAR MSB in tree value
        while ((huffTreeValue & 0x8000) != 0) {
            // read another byte if exhausted all 8 bits in buffer
            if (bitOffset == 0x7) {
                huffmanBuffer = peekByte();
            }

            // LSB = 0 -> left tree ------ LSB = 1 -> right tree
            int bitmask = 0x1 << bitOffset;
            boolean useLeftTree = (huffmanBuffer & bitmask) == 0;

            // update state of and position in the Huffman buffer
            bitOffset = (bitOffset - 1) & 0x7;

            // update current position in the ROM
            if (bitOffset == 0x7) {
                currByteOffset++;
                romFile.readUnsignedByte();
            }

            // read from either the left or right tree -- the >> 1 is for how
            // the offsets are BYTE offsets instead of WORD offsets
            int index = (huffTreeValue & 0x7FFF) >> 1;
            if (useLeftTree) {
                huffTreeValue = huffLeftTrees[index];
            }
            else {
                huffTreeValue = huffRightTrees[index];
            }
        }
        // System.out.printf("%s\n", huffCode);
        return huffTreeValue & 0x1FFF;
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------

    private static void addAtlasHeader(String tableFilename) throws IOException {
        scriptOutput.write("#VAR(Table, TABLE)");
        scriptOutput.newLine();
        // use the table file that was included when running this tool
        String tableReferenceFormat = "#ADDTBL(\"%s\", Table)\n";
        scriptOutput.write(String.format(tableReferenceFormat, tableFilename));
        scriptOutput.write("#ACTIVETBL(Table)\n\n");

        scriptOutput.write("#VAR(Ptr, CUSTOMPOINTER)\n");
        scriptOutput.write("#CREATEPTR(Ptr, \"LINEAR\", $400000, 24)\n\n");
        scriptOutput.write("#VAR(PtrTbl, POINTERTABLE)\n");
        scriptOutput.write("#PTRTBL(PtrTbl, $15BB5, 3, Ptr)\n\n");

        String commentLine = "// -----------------------------------------------------------------------------\n";
        scriptOutput.write(commentLine);
        scriptOutput.write(commentLine);
        scriptOutput.newLine();

        scriptOutput.write("// Insert uncompressed script in 5th MB, after end of ROM\n");
        scriptOutput.write("#JMP($400000)\n\n");
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------

    private static void readScriptPointers() throws IOException {
        romFile.seek(PTR_TABLE_OFFSET);
        scriptPtrs = new int[NUM_PTRS];
        for (int ptrNum = 0; ptrNum < NUM_PTRS; ptrNum++) {
            int ptr = romFile.readUnsignedByte();
            ptr |= (romFile.readUnsignedByte() << 8);
            ptr |= (romFile.readUnsignedByte() << 16);
            scriptPtrs[ptrNum] = ptr;
        }
    }

    private static String blockComment = "// Block %4X (%3X-%d) @ 0x%6X-%d\n";
    private static void printBlockComment(int ptrNum, int numStringsFound) throws IOException {
        int blockNum = getBlockNum(ptrNum, numStringsFound);
        if (blockNum > FINAL_BLOCK_NUM) {
            return;
        }

        scriptOutput.write(String.format(blockComment, blockNum, ptrNum,
            numStringsFound, currByteOffset, bitOffset));
    }

    private static int getBlockNum(int ptrNum, int numStringsFound) {
        return (ptrNum * NUM_STRINGS_PER_PTR) | numStringsFound;
    }

    private static void readTextForPointer(int ptrNum) throws IOException {
        int ptrTableEntry = scriptPtrs[ptrNum];
        int cpuOffset = (ptrTableEntry >> 3) + SCRIPT_START;
        bitOffset = ptrTableEntry & 0x7;

        currByteOffset = cpuOffset & 0x3FFFFF;
        romFile.seek(currByteOffset);

        int numStringsFound = 0;
        printBlockComment(ptrNum, numStringsFound);
        scriptOutput.write("#WRITE(PtrTbl)\n");
        while (numStringsFound < NUM_STRINGS_PER_PTR) {
            // check for final text block
            if (getBlockNum(ptrNum, numStringsFound) > FINAL_BLOCK_NUM) {
                break;
            }

            int charEncoding = readCharacter();
            printChar(charEncoding);
            switch (charEncoding) {
                // check for string terminators
                case 0xAC:
                case 0xAE:
                    numStringsFound++;
                    scriptOutput.newLine();
                    scriptOutput.newLine();
                    if (numStringsFound != NUM_STRINGS_PER_PTR)
                        printBlockComment(ptrNum, numStringsFound);
                    break;

                // check for line break
                case 0xAD:
                    scriptOutput.newLine();
                    break;

                // fill in more as you discover what control codes do what
                default:
                    break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------

    public static void main(String args[]) throws IOException {
        if (args.length != 2) {
            System.out.println("Sample usage: java DragonQuest6ScriptDumper rom_name table_file");
            return;
        }

        String romFilename = args[0];
        String tableFilename = args[1];

        romFile = new RandomAccessFile(romFilename, "r");
        scriptOutput = new BufferedWriter(new FileWriter("script dump for '" + romFilename + "'.txt"));

        readTableFile(tableFilename);
        getHuffmanTreeData();

        addAtlasHeader(tableFilename);
        readScriptPointers();
        for (int i = 0; i < scriptPtrs.length; i++) {
            readTextForPointer(i);
        }

        romFile.close();
        scriptOutput.flush();
        scriptOutput.close();
    }
}
