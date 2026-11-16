import java.io.File;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
// Instrumento de lectura: usa PDFBox ya presente en las dependencias, sin instalar paquetes.
class Cu11ReceiptText {
 public static void main(String[] args) throws Exception {
  try(var doc=Loader.loadPDF(new File(args[0]))) {System.out.write(new PDFTextStripper().getText(doc).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
}
