// Hidden GL round-trip of the production bulk RGBA layout. Not a Minecraft/Iris test.
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;
public final class TextureProbe {
    public static void main(String[] args){
        if(!GLFW.glfwInit())throw new IllegalStateException("GLFW init failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long w=GLFW.glfwCreateWindow(64,64,"PvZ private texture probe",0,0);
        if(w==0)throw new IllegalStateException("GL window failed");
        ByteBuffer upload=MemoryUtil.memAlloc(800*600*4),read=MemoryUtil.memAlloc(800*600*4);
        try {
            GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();
            byte[] rgba=new byte[800*600*4];for(int y=0,i=0;y<600;y++)for(int x=0;x<800;x++,i+=4){rgba[i]=(byte)x;rgba[i+1]=(byte)y;rgba[i+2]=(byte)(x^y);rgba[i+3]=(byte)255;}
            int id=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,800,600,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L);
            GL11.glPixelStorei(3314,0);GL11.glPixelStorei(3316,0);GL11.glPixelStorei(3315,0);GL11.glPixelStorei(3317,4);
            var times=new ArrayList<Double>();
            for(int i=0;i<360;i++){long began=System.nanoTime();upload.clear();upload.put(rgba).flip();GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,800,600,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,upload);GL11.glFinish();if(i>=60)times.add((System.nanoTime()-began)/1e6);}
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,read);
            byte[] actual=new byte[rgba.length];read.get(actual);if(!Arrays.equals(rgba,actual))throw new AssertionError("RGBA, alpha, orientation mismatch");
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            Collections.sort(times);System.out.printf(Locale.ROOT,"{\"rgbaRoundTrip\":true,\"iterations\":300,\"uploadMedianMs\":%.3f,\"uploadP95Ms\":%.3f}%n",times.get(150),times.get(285));GL11.glDeleteTextures(id);
        }finally{MemoryUtil.memFree(upload);MemoryUtil.memFree(read);GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();}
    }
}
