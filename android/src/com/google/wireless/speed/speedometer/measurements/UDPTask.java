// Copyright 2012 Google Inc. All Rights Reserved.


package com.google.wireless.speed.speedometer.measurements;

import com.google.wireless.speed.speedometer.MeasurementDesc;
import com.google.wireless.speed.speedometer.MeasurementError;
import com.google.wireless.speed.speedometer.MeasurementResult;
import com.google.wireless.speed.speedometer.MeasurementTask;
import com.google.wireless.speed.speedometer.SpeedometerApp;
import com.google.wireless.speed.speedometer.util.PhoneUtils;

import android.content.Context;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.util.Date;
import java.util.Map;
import java.io.*;


/**
 * 
 * The UDPTask provides two types of measurements:
 * 
 * 1. UDP UP: the phone sends sends a burst of UDPBurstCount UDP packets
 * and waits for a response from the server that includes the number of
 * packets that the server received
 * 
 * 2. UDP DOWN: the phone sends a request to a remote server on UDP PORT 
 * and the server responds by sending a burst of UDPBurstCount packets. 
 * The size of each packet is packetSizeByte
 * 
 * @author aterzis@google.com (Andreas Terzis)
 *
 */

public class UDPTask extends MeasurementTask {

  public static final String TYPE_UP = "UDP_Up_measurement";
  public static final String TYPE_DOWN = "UDP_Down_measurement";
  public static final String DESCRIPTOR_UP = "UDP Up";
  public static final String DESCRIPTOR_DOWN = "UDP Down";

  private static final int DEFAULT_UDP_PACKET_SIZE = 500;
  private static final int DEFAULT_UDP_BURST = 10;
  private static final int PORT=31341;
  private static final int MIN_PACKETSIZE = 20;
  
  private static final int RCV_TIMEOUT = 3000; // in msec

  private static final int PKT_ERROR =1;
  private static final int PKT_RESPONSE=2;
  private static final int PKT_DATA=3;
  private static final int PKT_REQUEST=4;

  private String targetIp = null;

  private static int seq = 1;

  /**
   * Encode UDP specific parameters, along with common parameters 
   * inherited from MeasurementDesc
   * 
   * @author aterzisg@google.com (Andreas Terzis)
   *
   */
  public static class UDPDesc extends MeasurementDesc {     
    // Declare static parameters specific to SampleMeasurement here

    public int packetSizeByte = UDPTask.DEFAULT_UDP_PACKET_SIZE;  
    public int UDPBurstCount  = UDPTask.DEFAULT_UDP_BURST;
    public String target = null;
    public boolean Up = false;

    public UDPDesc(String key, Date startTime,
        Date endTime, double intervalSec, long count, 
        long priority, Map<String, String> params) 
            throws InvalidParameterException {
      super(UDPTask.TYPE_UP, key, startTime, endTime, intervalSec, count,
          priority, params);  
      initalizeParams(params);
      if (this.target == null || this.target.length() == 0) {
        throw new InvalidParameterException("UDPTask null target");
      }
    }

    /**
     * There are three UDP specific parameters:
     * 
     * 1. "direction": "up" if this is an uplink measurement. or "down" otherwise
     * 2. "packet_burst": how many packets should a up/down burst have
     * 3. "packet_size_byte": the size of each packet in bytes
     */
    @Override
    protected void initalizeParams(Map<String, String> params) {
      if (params == null) {
        return;
      }

      this.target = params.get("target");

      try {        
        String val = null;
        if ((val = params.get("packet_size_byte")) != null && val.length() > 0 
            && Integer.parseInt(val) > 0) {
          this.packetSizeByte = Integer.parseInt(val);
          if (this.packetSizeByte < MIN_PACKETSIZE)
            this.packetSizeByte = MIN_PACKETSIZE;
        }
        if ((val = params.get("packet_burst")) != null && val.length() > 0 &&
            Integer.parseInt(val) > 0) {
          this.UDPBurstCount = Integer.parseInt(val);  
        }
      } catch (NumberFormatException e) {
        throw new InvalidParameterException("UDPTask invalid params");
      }

      String dir = null;
      if ((dir = params.get("direction")) != null && dir.length() > 0) {
        if (dir.compareTo("up") == 0)
          this.Up = true;
      }
    }


    @Override
    public String getType() {
      if (this.Up)
        return UDPTask.TYPE_UP;
      else
        return UDPTask.TYPE_DOWN;
    }
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return UDPDesc.class;
  }

  public UDPTask(MeasurementDesc desc, Context parent) {
    super(new UDPDesc(desc.key, desc.startTime, desc.endTime, 
        desc.intervalSec, desc.count, desc.priority, desc.parameters), parent);
  }

  /**
   * Make a deep cloning of the task
   */
  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    UDPDesc newDesc = new UDPDesc(desc.key, desc.startTime, 
        desc.endTime, desc.intervalSec, desc.count, desc.priority, 
        desc.parameters);
    return new UDPTask(newDesc, parent);
  }
  
  /**
   * Opens a datagram (UDP) socket
   * 
   * @return a datagram socket used for sending/receiving
   * @throws MeasurementError if an error occurs
   */
  
  private DatagramSocket openSocket() throws MeasurementError {
    UDPDesc desc = (UDPDesc) measurementDesc;
    DatagramSocket sock = null;
    
    // Open datagram socket
    try {
      sock = new DatagramSocket();
    } catch (SocketException e) {
      throw new MeasurementError("Socket creation failed");
    }
    
    return sock;
  }
  
  /**
   * Opens a Datagram socket to the server included in the UDPDesc
   * and sends a burst of UDPBurstCount packets, each of size packetSizeByte.
   * 
   * @return a Datagram socket that can be used to receive the 
   * server's response
   * 
   * @throws MeasurementError if an error occurred.
   */
  private DatagramSocket sendUpBurst() throws MeasurementError {
    UDPDesc desc = (UDPDesc) measurementDesc;
    InetAddress addr = null;
    ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(byteOut);
    DatagramSocket sock = null;
    
    // Resolve the server's name
    try {
      addr = InetAddress.getByName(desc.target);
      targetIp = addr.getHostAddress();
    } catch (UnknownHostException e) {
      throw new MeasurementError("Unknown host " + desc.target);
    }
    
    sock = openSocket();
    
    // Send burst
    for (int i = 0; i < desc.UDPBurstCount; i++) {
      byteOut.reset();
      try {
        dataOut.writeInt(UDPTask.PKT_DATA);
        dataOut.writeInt(desc.UDPBurstCount);
        dataOut.writeInt(i);
        dataOut.writeInt(desc.packetSizeByte);
        dataOut.writeInt(seq);
        for (int j = 0; j < desc.packetSizeByte - UDPTask.MIN_PACKETSIZE; j++) {
          // Fill in the rest of the packet with zeroes.
          // TODO: There has to be a better way to do this
          dataOut.write(0);
        }
      } catch (IOException e) {
        sock.close();
        throw new MeasurementError("Error creating message to " + desc.target);
      }

      byte[] data = byteOut.toByteArray();
      DatagramPacket packet = new DatagramPacket(data, data.length, addr, UDPTask.PORT);
      // TODO: Can we reuse packet?
      try {
        sock.send(packet);
      } catch (IOException e) {
        sock.close();
        throw new MeasurementError("Error sending " + desc.target);
      }
      Log.i(SpeedometerApp.TAG, "Sent packet pnum:" + i + " to " + desc.target + ": " + targetIp);
    } // for()

    try {
      byteOut.close();
    } catch (IOException e) {
      sock.close();
      throw new MeasurementError("Error closing outputstream to: " + desc.target);
    }
    return sock;
  }
  
  /**
   * Receive a response from the server after the burst of uplink packets was
   * sent, parse it, and return the number of packets the server received.
   * 
   * @param sock the socket used to receive the server's response
   * @return the number of packets the server received
   * 
   * @throws MeasurementError if an error or a timeout occurs
   */
  private int recvUpResponse(DatagramSocket sock) throws MeasurementError {
    UDPDesc desc = (UDPDesc) measurementDesc;
    int ptype, burstsize,pktnum;
    
    // Receive response
    Log.i(SpeedometerApp.TAG, "Waiting for UDP response from " + desc.target + ": " + targetIp);

    byte buffer[] = new byte[UDPTask.MIN_PACKETSIZE];
    DatagramPacket recvpacket = new DatagramPacket(buffer, buffer.length);
    
    try {
      sock.setSoTimeout(RCV_TIMEOUT);
      sock.receive(recvpacket);
    } catch (SocketException e1) {
      sock.close();
      throw new MeasurementError("Timed out reading from " + desc.target);
    } catch (IOException e) {
      sock.close();
      throw new MeasurementError("Error reading from " + desc.target);
    }

    ByteArrayInputStream byteIn =
        new ByteArrayInputStream(recvpacket.getData(), 0, recvpacket.getLength());
    DataInputStream dataIn = new DataInputStream(byteIn);
    
    try {
      ptype = dataIn.readInt();
      burstsize = dataIn.readInt();
      pktnum = dataIn.readInt();
    } catch (IOException e) {
      sock.close();
      throw new MeasurementError("Error parsing response from " + desc.target);
    }
    
    Log.i(SpeedometerApp.TAG, "Recv UDP response from " + desc.target + " type:" + ptype + " burst:"
        + burstsize + " pktnum:" + pktnum);
  
    return pktnum;
  }
  
  /**
   * Opens a datagram socket to the server in the UDPDesc and requests the
   * server to send a burst of UDPBurstCount packets, each of packetSizeByte
   * bytes. 
   * 
   * @return the datagram socket used to receive the server's burst
   * @throws MeasurementError if an error occurs
   */

  private DatagramSocket sendDownRequest() throws MeasurementError {
    UDPDesc desc = (UDPDesc) measurementDesc;
    DatagramPacket packet;
    InetAddress addr = null;
    ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(byteOut);
    DatagramSocket sock = null;
    
    // Resolve the server's name
    try {
      addr = InetAddress.getByName(desc.target);
      targetIp = addr.getHostAddress();
    } catch (UnknownHostException e) {
      throw new MeasurementError("Unknown host " + desc.target);
    }

    sock = openSocket();

    Log.i(SpeedometerApp.TAG, "Requesting UDP burst:" + desc.UDPBurstCount
        + " pktsize: " + desc.packetSizeByte
        + " to " + desc.target + ": " + targetIp);

    try {
      dataOut.writeInt(UDPTask.PKT_REQUEST);
      dataOut.writeInt(desc.UDPBurstCount);
      dataOut.writeInt(0);
      dataOut.writeInt(desc.packetSizeByte);
      dataOut.writeInt(seq);
    } catch (IOException e) {
      sock.close();
      throw new MeasurementError("Error creating message to " + desc.target);
    }

    byte []data = byteOut.toByteArray();
    packet = new DatagramPacket(data, data.length, addr, UDPTask.PORT);

    try {   
      sock.send(packet);
    } catch (IOException e) {
      sock.close();
      throw new MeasurementError("Error sending " + desc.target);
    }

    try {
      byteOut.close();
    } catch (IOException e){
      sock.close();
      throw new MeasurementError("Error closing Output Stream to "
          + desc.target);
    }

    return sock;
  }
  
  /**
   * Receives a burst from the remote server and counts the number of packets
   * that were received. 
   * 
   * @param sock the datagram socket that can be used to receive the server's
   * burst
   * 
   * @return the number of packets received from the server
   * @throws MeasurementError if an error occurs
   */

  private int recvDownResponse(DatagramSocket sock) throws MeasurementError {
    int pktrecv =0;
    UDPDesc desc = (UDPDesc) measurementDesc;

    // Receive response
    Log.i(SpeedometerApp.TAG, "Waiting for UDP burst from " + 
        desc.target);

    byte buffer[] = new byte[desc.packetSizeByte];
    DatagramPacket recvpacket = new DatagramPacket (buffer, buffer.length);

    for (int i = 0; i < desc.UDPBurstCount; i++) {
      int ptype, burstsize, pktnum; 

      try {
        sock.setSoTimeout (RCV_TIMEOUT);
        sock.receive (recvpacket);
      } catch (IOException e) {
        break;
      }

      ByteArrayInputStream byteIn = 
          new ByteArrayInputStream (recvpacket.getData (), 0, 
              recvpacket.getLength ());
      DataInputStream dataIn = new DataInputStream (byteIn);

      try {
        ptype = dataIn.readInt();
        burstsize = dataIn.readInt();
        pktnum = dataIn.readInt();
      } catch (IOException e) {
        sock.close();
        throw new MeasurementError("Error parsing response from " + 
            desc.target);       
      }

      Log.i(SpeedometerApp.TAG, "Recv UDP response from " + desc.target + 
          " type:"+ptype + " burst:"+burstsize + " pktnum:" + pktnum);

      if (ptype == UDPTask.PKT_DATA) {
        pktrecv++;
      }

      try {
        byteIn.close();
      } catch (IOException e) {
        sock.close();
        throw new MeasurementError("Error closing input stream from " + 
            desc.target);
      }
    } // for()

    return pktrecv;
  }

  /**
   * Depending on the type of measurement, indicated by desc.Up, perform
   * an uplink/downlink measurement 
   * 
   * @return the measurement's results
   * @throws MeasurementError if an error occurs
   */
  
  @Override
  public MeasurementResult call() throws MeasurementError {
    DatagramSocket socket = null;
    DatagramPacket packet;
    float response =0.0F;
    int pktrecv =0;
    boolean isMeasurementSuccessful = false;
    String type;

    UDPDesc desc = (UDPDesc) measurementDesc;
    PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();

    if (desc.Up == true) {
      socket = sendUpBurst();
      pktrecv = recvUpResponse(socket);

      response = pktrecv/(float)desc.UDPBurstCount;
      isMeasurementSuccessful = true;
      type = UDPTask.TYPE_UP;
    } else {
      socket = sendDownRequest();
      pktrecv = recvDownResponse(socket);

      response = pktrecv/(float)desc.UDPBurstCount;
      isMeasurementSuccessful = true;
      type = UDPTask.TYPE_DOWN;
    }
    
    MeasurementResult result = new MeasurementResult(phoneUtils.getDeviceInfo().deviceId,
        phoneUtils.getDeviceProperty(), type, 
        System.currentTimeMillis() * 1000, isMeasurementSuccessful, 
        this.measurementDesc);
    
    result.addResult("target_ip", targetIp);
    result.addResult("PRR", response);
    // Update the sequence number to be used by the next burst
    seq++;

    socket.close();

    return result;
  }

  @Override
  public String getType() {
    UDPDesc desc = (UDPDesc) measurementDesc;
    
    if (desc.Up)
      return UDPTask.TYPE_UP;
    else
      return UDPTask.TYPE_DOWN;
  }

  /**
   * Returns a brief human-readable descriptor of the task.
   */
  @Override
  public String getDescriptor() {
    UDPDesc desc = (UDPDesc) measurementDesc;
    
    if (desc.Up)
      return UDPTask.DESCRIPTOR_UP;
    else
      return UDPTask.DESCRIPTOR_DOWN;
  }

  private void cleanUp() {

  }


  /**
   * Stop the measurement, even when it is running. Should release all acquired
   * resource in this function. There should not be side effect if the measurement 
   * has not started or is already finished.
   */
  @Override
  public void stop() {
    cleanUp();
  }

  /**
   * This will be printed to the device log console. Make sure it's well structured and
   * human readable
   */
  @Override
  public String toString() {
    UDPDesc desc = (UDPDesc) measurementDesc;
    String resp;
    
    if (desc.Up) {
      resp = "[UDPUp]\n";
    } else {
      resp = "[UDPDown]\n";
    }
      
    resp += " Target: " + desc.target + "\n  Interval (sec): " + 
    desc.intervalSec + "\n  Next run: " + desc.startTime;
    
    return resp;
  }
}