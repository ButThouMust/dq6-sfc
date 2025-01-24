
// code adapted from scan.cpp from Guest

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Stack;
import java.io.BufferedReader;

public class GenerateHuffCodes {

    // *************************************************************************
    // Useful constants
    // *************************************************************************

    private static final int HUFF_LEFT_OFFSET = 0x167be;
    private static final int HUFF_RIGHT_OFFSET = 0x1700e;
    private static final int HUFF_TABLE_ENTRIES = 0x428;

    // TODO: Kamaitachi table file has 1871 entries for characters, possibly
    // more depending on how many control codes are used; set to 2000 for now
    private static final int TABLE_FILE_ENTRIES = 2000;

    private static final String LEFT_BIT = "1";
    private static final String RIGHT_BIT = "0";

    // *************************************************************************
    // Options for how to output to text files
    // *************************************************************************

    // print the data either CSV-style (false) or "pretty-print" style (true)
    private static boolean PRETTY_PRINT = true;

    // print the Huffman code data sorted by table value (true) or sorted mostly
    // by Huffman code length (false)
    private static boolean SORT_BY_TBL_VALUE = true;

    // *************************************************************************
    // Data structure for describing general Huffman nodes
    // *************************************************************************

    private static class HuffmanNode {
        // data for this node's L/R subtrees
        short l_data;
        short r_data;

        // ROM offsets for this node's L/R subtrees; find L/R data here
        long leftHuffOffset;
        long rightHuffOffset;

        // Huffman codes (i.e. tree traversal paths) to this node's L/R subtrees
        // use String here because Huffman codes are variable-length and can
        // start with a 0; numerical types would disregard any starting 0s
        String leftHuffCode; 
        String rightHuffCode;
    }
    private static ArrayList<HuffmanNode> tree_data;

    // *************************************************************************
    // Data structure for describing specifically the Huffman leaf nodes
    // *************************************************************************

    private static class HuffmanLeaf implements Comparable<HuffmanLeaf>{
        short data;
        long huffOffset;
        String huffCode;
        String encoding;

        private HuffmanLeaf(short data, long huffOffset, String huffCode) {
            this.data = (short) (data & 0x1FFF);
            this.huffOffset = huffOffset;
            this.huffCode = huffCode;
            this.encoding = getEncoding(data);
        }

        // by default, compare leaves by their data values ("table file sort")
        public int compareTo(HuffmanLeaf other) {
            return data - other.data;
        }
    }
    private static ArrayList<HuffmanLeaf> huffLeaves;

    // *************************************************************************
    // Data structures for contents of table file
    // *************************************************************************

    private static ArrayList<Short> tableHexValues;
    private static ArrayList<String> encodings;

    // *************************************************************************
    // Code for reading in the contents of the table file
    // *************************************************************************

    private static void readTableFile() {
        try {
            // this file is sorted in increasing order by character code
            BufferedReader tableFileStream = new BufferedReader(new FileReader("dq6 jp.tbl"));

            // basic format of a table file line is "[hex value]=[character]\n"
            String line;
            while ((line = tableFileStream.readLine()) != null) {
                if (line.equals(""))
                    continue;

                // game has a '=' character, but I used the Japanese char '＝'
                String split[] = line.split("=");
                if (split.length < 2) continue;

                short value = Short.parseShort(split[0], 16);
                tableHexValues.add(value);
                encodings.add(split[1]);
            }

            tableFileStream.close();
        }
        catch (IOException e) {
            System.err.println(e.toString());
        }
        return;
    }

    private static String getEncoding(short data) {
        data &= 0x1FFF;
        int index = Collections.binarySearch(tableHexValues, data);

        String encoding = "N/A";
        if (index >= 0) {
            encoding = encodings.get(index);
        }
        return encoding;
    }

    // *************************************************************************
    // Code for reading in the raw Huffman tree data, and outputting it to text
    // *************************************************************************

    private static void getTreeData() {
        try {
            RandomAccessFile romStream = new RandomAccessFile("Dragon Quest VI - Maboroshi no Daichi (Japan).sfc", "r");
    
            // start reading data for the left subtrees for the Huffman tree
            romStream.seek(HUFF_LEFT_OFFSET);
            for (int i = 0; i < HUFF_TABLE_ENTRIES; i++) {
                tree_data.add(new HuffmanNode());
                HuffmanNode node = tree_data.get(i);
    
                // data is stored in little-endian order; convert to big-endian
                node.leftHuffOffset = romStream.getFilePointer();
                node.l_data = (short) romStream.readUnsignedByte();
                node.l_data |= (romStream.readUnsignedByte() << 8);

                node.leftHuffCode = "";
            }
    
            romStream.seek(HUFF_RIGHT_OFFSET);
            for (int i = 0; i < HUFF_TABLE_ENTRIES; i++) {
                HuffmanNode node = tree_data.get(i);

                node.rightHuffOffset = romStream.getFilePointer();
                node.r_data = (short) romStream.readUnsignedByte();
                node.r_data |= (romStream.readUnsignedByte() << 8);

                node.rightHuffCode = "";
            }
    
            romStream.close();
    
        }
        catch (IOException e) {
            System.err.println(e.toString());
        }
        return;
    }

    // describes the raw Huffman tree data with a standard format on every line
    // prints: tree index number, left/right tree values and ROM offsets
    private static String treeDataLineOutput(int index, HuffmanNode node) {
        String format = PRETTY_PRINT ? "tree @ %03X: %4X @ 0x%4X, %4X @ 0x%4X\n"
                                     : "%03X,%4X,0x%4X,%4X,0x%4X\n";
        return String.format(format, index, node.l_data, node.leftHuffOffset, node.r_data, node.rightHuffOffset);
    }

    // outputs all the lines as described above to a text file
    private static void treeDataFileOutput() throws IOException {
        FileWriter treeOutputWriter = new FileWriter("raw huffman tree data.txt");

        for (int i = 0; i < HUFF_TABLE_ENTRIES; i++) {
            HuffmanNode node = tree_data.get(i);
            treeOutputWriter.write(treeDataLineOutput(i, node));
            treeOutputWriter.flush();
        }
        treeOutputWriter.close();
    }

    // *************************************************************************
    // Code for traversing the Huffman tree and outputting the codes to text
    // *************************************************************************

    private static void getHuffLeaves() {
        // get all the Huffman leaves by traversing the tree
        Stack<HuffmanNode> stack = new Stack<HuffmanNode>();
        HuffmanNode huffmanRoot = tree_data.get(HUFF_TABLE_ENTRIES - 1);
        stack.push(huffmanRoot);

        while (!stack.isEmpty()) {
            HuffmanNode node = stack.pop();

            // examine left subtree, push onto stack if data value is >= 0x8000
            // if not, this is a leaf node, so create a HuffmanLeaf object
            String leftHuff = node.leftHuffCode + LEFT_BIT;
            if (node.l_data < 0) {
                HuffmanNode leftNode = tree_data.get((node.l_data & 0x7FFF) >> 1);
                leftNode.leftHuffCode = leftHuff;
                leftNode.rightHuffCode = leftHuff;
                stack.push(leftNode);
            }
            else {
                node.leftHuffCode = leftHuff;
                HuffmanLeaf leaf = new HuffmanLeaf(node.l_data, node.leftHuffOffset, node.leftHuffCode);
                huffLeaves.add(leaf);
            }

            // same process but with the right subtree
            String rightHuff = node.rightHuffCode + RIGHT_BIT;
            if (node.r_data < 0) {
                HuffmanNode rightNode = tree_data.get((node.r_data & 0x7FFF) >> 1);
                rightNode.leftHuffCode = rightHuff;
                rightNode.rightHuffCode = rightHuff;
                stack.push(rightNode);
            }
            else {
                node.rightHuffCode = rightHuff;
                HuffmanLeaf leaf = new HuffmanLeaf(node.r_data, node.rightHuffOffset, node.rightHuffCode);
                huffLeaves.add(leaf);
            }

            // Java has no "unsigned short" type, so weird comparison:
            // 0x0000 thru 0x8000 = 0 thru 32768;   leaf node, done
            // 0x8001 thru 0xFFFF = -32767 thru -1; inner node, push
        }
    }

    // describes a Huffman code with a standard format on every line
    // prints: table file value, Huffman code, its length, its character or
    //         or control code (if applicable), and final ROM offset in tree
    private static String huffCodeLineOutput(HuffmanLeaf leaf) {
        // obtain the encoding, if it has one (print "N/A" if no)
        // assume that order of encodings' values in table file is sorted
        String format = PRETTY_PRINT ? "Huff code for %4X '%s'\tis %2d bits @ 0x%4X: %s\n"
                                     : "%4X,%s,%2d,0x%4X,%s\n";
        return String.format(format, leaf.data, leaf.encoding, leaf.huffCode.length(), leaf.huffOffset, leaf.huffCode);
    }

    // outputs all the lines as described above to a text file
    private static void huffCodesFileOutput() throws IOException {
        FileWriter huffCodeWriter = new FileWriter("huffman code dump.txt");

        if (SORT_BY_TBL_VALUE) {
            Collections.sort(huffLeaves);
        }

        for (int i = 0; i < huffLeaves.size(); i++) {
            HuffmanLeaf leaf = huffLeaves.get(i);

            huffCodeWriter.write(huffCodeLineOutput(leaf));
            huffCodeWriter.flush();
        }
        huffCodeWriter.close();
    }

    // *************************************************************************
    // *************************************************************************

    public static void main(String[] args) {
        tree_data = new ArrayList<>(HUFF_TABLE_ENTRIES);
        huffLeaves = new ArrayList<>(HUFF_TABLE_ENTRIES);

        encodings = new ArrayList<>(TABLE_FILE_ENTRIES);
        tableHexValues = new ArrayList<>(TABLE_FILE_ENTRIES);

        readTableFile();
        getTreeData();
        getHuffLeaves();

        try {
            treeDataFileOutput();
            huffCodesFileOutput();
        }
        catch (IOException e) {
            System.err.println(e.toString());
        }
    }
}
