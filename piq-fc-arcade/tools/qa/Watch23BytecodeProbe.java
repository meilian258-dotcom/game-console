import com.google.gson.Gson;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Reads final class bytes only. ASM normalizes constant-pool widths, labels and exception offsets. */
public final class Watch23BytecodeProbe {
    public static void main(String[] args)throws Exception {
        if(args.length<2)throw new IllegalArgumentException("jar followed by explicit class paths");
        var result=new LinkedHashMap<String,Object>();
        try(var jar=new ZipFile(Path.of(args[0]).toFile())){
            for(int i=1;i<args.length;i++){
                String name=args[i];var entry=jar.getEntry(name);if(entry==null)throw new IllegalArgumentException(name);
                ClassNode node=new ClassNode();new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
                var fields=new TreeMap<String,String>();for(FieldNode f:node.fields)fields.put(f.name+" "+f.desc,f.access+" "+f.signature+" "+String.valueOf(f.value));
                var methods=new TreeMap<String,String>();
                for(MethodNode m:node.methods){
                    Textifier text=new Textifier();m.accept(new TraceMethodVisitor(text));var buffer=new StringWriter();text.print(new PrintWriter(buffer));
                    methods.put(m.name+m.desc,m.access+" "+m.signature+" "+m.exceptions+"\n"+buffer);
                }
                result.put(name,Map.of("name",node.name,"access",node.access,"super",Objects.toString(node.superName,""),"interfaces",node.interfaces,"fields",fields,"methods",methods));
            }
        }
        System.out.println(new Gson().toJson(result));
    }
}
