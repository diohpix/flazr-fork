

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class Sample {
	public static void main(String args[]) throws IOException{
		ServerSocket s = new ServerSocket(1937);
		while(true){
			Socket a = s.accept();
			System.out.println("connect");
			InputStream i = a.getInputStream();
			OutputStream o = a.getOutputStream();
			byte []buf =new byte[8192];
			
			
			int c = 0;
			while ( (c = i.read(buf))>0){
				System.out.println("READ "+c);
				System.out.println(new String(buf,0,c));
			}
		}
	}
}
