package org.microbit.android.partialflashing;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;


/**
 * Created by Sam Kent on 01/11/2017.
 *
 * A Class to manipulate micro:bit hex files
 * Focused towards stripping a file down to it's PXT section for use in Partial Flashing
 *
 *   (c) 2017 - 2021, Micro:bit Educational Foundation and contributors
 *
 *  SPDX-License-Identifier: MIT
 *
 */

public class HexUtils {
    private final static String TAG = HexUtils.class.getSimpleName();
        
    private final static int INIT = 0;
    private final static int INVALID_FILE = 1;
    private final static int NO_PARTIAL_FLASH = 2;
    public int status = INIT;

    FileInputStream fis = null;
    BufferedReader reader = null;
    List<String> hexLines = new ArrayList<String>();

    public HexUtils(String filePath){
        // Hex Utils initialization
        // Open File
        try {
          if(!openHexFile(filePath)){
                  status = INVALID_FILE;
          }
        } catch(Exception e) {
          Log.e(TAG, "Error opening file: " + e);
        }
    }

    /*
        A function to open a hex file for reading
        @param filePath - A string locating the hex file in use
        @return true  - if file opens
                false - if file cannot be opened
     */
    public Boolean openHexFile(String filePath) throws IOException {
        // Open connection to hex file
        try {
            fis = new FileInputStream(filePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }

        // Create reader for hex file
        reader = new BufferedReader(new InputStreamReader(fis));
        String line;
        while((line = reader.readLine()) != null) {
            hexLines.add(line);
        }
        reader.close();
        return true;
    }

    /* 
     * A function to find the length of the hex file
     * @param none
     * @ return the size (# of lines) in the hex file
     */
    public int numOfLines() {
            return hexLines.size();
    }
    
    /* 
     * A function to search for data in a hex file
     * @param the _string_ of data to search for
     * @return the index of the data. -1 if not found.
     */
    public int searchForData(String search) throws IOException {
        // Iterate through
        ListIterator i = hexLines.listIterator();
        while (i.hasNext()) {
            // Have to call nextIndex() before next()
            int index = i.nextIndex();

            // Return index if successful
            if(i.next().toString().contains(search)){ return index; }
        }

        // Return -1 if no match
        return -1;
    }

    /*
     * A function to search for data in a hex file
     * @param the _string_ of data to search for
     * @return the index of the data. -1 if not found.
     */
    public int searchForDataRegEx(String search) throws IOException {
        // Iterate through
        ListIterator i = hexLines.listIterator();
        while (i.hasNext()) {
            // Have to call nextIndex() before next()
            int index = i.nextIndex();

            // Return index if successful
            String match = i.next().toString();
            if(match.matches(search)){
                return index;
            }
        }

        // Return -1 if no match
        return -1;
    }

    /*
     * A function to search for an address in a hex file
     * @param search the address to search for
     * @return the index of the address. -1 if not found.
     */
    public int searchForAddress( long address) throws IOException {
        long lastBaseAddr = 0;
        String data;
        // Iterate through
        ListIterator i = hexLines.listIterator();
        while ( i.hasNext()) {
            // Have to call nextIndex() before next()
            int index = i.nextIndex();
            String line = i.next().toString();

            switch (getRecordType(line)) {
                case 2: {               // Extended Segment Address
                    data = getRecordData(line);
                    if ( data.length() != 4) {
                        return -1;
                    }
                    int hi = Integer.parseInt( data.substring(0, 1), 16);
                    int lo = Integer.parseInt( data.substring(1), 16);
                    lastBaseAddr = (long) hi * (long) 0x1000 + (long) lo * (long) 0x10;
                    if ( lastBaseAddr > address) {
                        return -1;
                    }
                    break;
                }
                case 4: {
                    data = getRecordData(line);
                    if ( data.length() != 4) {
                        return -1;
                    }
                    lastBaseAddr = Integer.parseInt( data, 16);
                    lastBaseAddr *= (long) 0x10000;
                    if ( lastBaseAddr > address) {
                        return -1;
                    }
                    break;
                }
                case 0:
                case 0x0D: {
                    if ( address - lastBaseAddr < 0x10000) {
                        long a = lastBaseAddr + getRecordAddress(line);
                        int  n = getRecordDataLength( line) / 2; // bytes
                        if ( a <= address && a + n > address) {
                            return index;
                        }
                    }
                    break;
                }
            }
        }

        // Return -1 if no match
        return -1;
    }

    /*
     * Returns data from an index
     * @param index
     * @return data as string
     */
    public String getDataFromIndex(int index) throws IOException {
            return getRecordData(hexLines.get(index)); 
    }

    /*
     * Returns record type from an index
     * @param index
     * @return type as int
     */
    public int getRecordTypeFromIndex(int index) throws IOException {
            return getRecordType(hexLines.get(index));
    }

    /*
     * Returns record address from an index
     * Note: does not include segment address
     * @param index
     * @return address as int
     */
    public int getRecordAddressFromIndex(int index) throws IOException {
            return getRecordAddress(hexLines.get(index));
    }

    /*
    Used to get the data length from a record
    @param Record as a String
    @return Data length as a decimal / # of chars
 */
    public int getRecordDataLengthFromIndex(int index){
        return getRecordDataLength(hexLines.get(index));
    }

    /*
     * Returns segment address from an index
     * @param index
     * @return address as int
     */
    public int getSegmentAddress(int index) throws IOException {
            // Look backwards to find current segment address
            int segmentAddress = -1;
            int cur = index;
            while(segmentAddress == -1) {
                if(getRecordTypeFromIndex(cur) == 4)
                    break;
                cur--;
            }

            // Return segment address
            return Integer.parseInt(getRecordData(hexLines.get(cur)), 16);
    }

    /*
        Used to get the data address from a record
        @param Record as a String
        @return Data address as a decimal
     */
    private int getRecordAddress(String record){
        String hexAddress = record.substring(3,7);
        return Integer.parseInt(hexAddress, 16);
    }

    /*
        Used to get the data length from a record
        @param Record as a String
        @return Data length as a decimal / # of chars
     */
    private int getRecordDataLength(String record){
        String hexLength = record.substring(1,3);
        int len = 2 * Integer.parseInt(hexLength, 16); // Num Of Bytes. Each Byte is represented by 2 chars hence 2*
        return len;
    }

    /*
    Used to get the record type from a record
    @param Record as a String
    @return Record type as a decimal
    */
    private int getRecordType(String record){
        try {
            String hexType = record.substring(7, 9);
            return Integer.parseInt(hexType, 16);
        } catch (Exception e){
            Log.e(TAG, "getRecordType " + e.toString());
            return 0;
        }
    }

    /*
    Used to get the data from a record
    @param Record as a String
    @return Data
    */
    private String getRecordData(String record){
        try {
            int len = getRecordDataLength(record);
            return record.substring(9, 9 + len);
        } catch (Exception e) {
            Log.e(TAG, "Get record data " + e.toString());
            return "";
        }
    }

    /*
    Number of lines / packets in file
     */
    public int numOfLines(String filePath) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath)));
        int lines = 0;
        while (!reader.readLine().contains("41140E2FB82FA2B")) lines++;
        reader.close();
        return lines;
    }


    /*
    Record to byte Array
    @param hexString string to convert
    @return byteArray of hex
     */
    public static byte[] recordToByteArray(String hexString, int offset, int packetNum){
        int len = hexString.length();
        byte[] data = new byte[(len/2) + 4];
        for(int i=0; i < len; i+=2){
            data[(i / 2) + 4] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4) + Character.digit(hexString.charAt(i+1), 16));
        }

        // WRITE Command
        data[0] = 0x01;

        data[1]   = (byte)(offset >> 8);
        data[2] = (byte)(offset & 0xFF);
        data[3] = (byte)(packetNum & 0xFF);

        Log.v(TAG, "Sent: " + data.toString());

        return data;
    }
}

