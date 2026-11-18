package id.stomake.ui;

import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Base64;
import java.util.Optional;

/** Reusable image picker with an actual draggable crop rectangle. Returns PNG Base64. */
public final class ImageCropDialog {
    private ImageCropDialog() {}

    public static String chooseCroppedBase64(Stage owner, String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Gambar", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
                new FileChooser.ExtensionFilter("Semua file", "*.*"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) return null;
        try {
            Image source = new Image(file.toURI().toString(), false);
            if (source.isError() || source.getWidth() < 1 || source.getHeight() < 1) throw new IllegalArgumentException("Gambar tidak bisa dibaca.");
            return showCrop(owner, source, title);
        } catch (Exception ex) {
            throw new IllegalStateException("Gagal membuka gambar: " + ex.getMessage(), ex);
        }
    }

    private static String showCrop(Stage owner, Image source, String title) throws Exception {
        double maxW = 760, maxH = 500;
        double scale = Math.min(maxW / source.getWidth(), maxH / source.getHeight());
        double displayW = Math.max(1, source.getWidth() * scale);
        double displayH = Math.max(1, source.getHeight() * scale);
        ImageView image = new ImageView(source);
        image.setFitWidth(displayW); image.setFitHeight(displayH); image.setPreserveRatio(false);
        image.setMouseTransparent(true);
        Pane canvas = new Pane(image);
        canvas.setPrefSize(displayW, displayH);
        canvas.setMinSize(displayW, displayH);
        canvas.setMaxSize(displayW, displayH);
        canvas.setStyle("-fx-background-color: #101116; -fx-border-color: #4c4e5c; -fx-border-width: 1;");
        Rectangle selection = new Rectangle(0, 0, 0, 0);
        selection.setFill(Color.color(0.35, 0.65, 1, 0.18));
        selection.setStroke(Color.web("#81b7ff")); selection.setStrokeWidth(2); selection.getStrokeDashArray().setAll(8.0, 5.0);
        selection.setMouseTransparent(true); canvas.getChildren().add(selection);
        final double[] start = new double[2];
        canvas.setOnMousePressed(e -> { start[0]=clamp(e.getX(),0,displayW); start[1]=clamp(e.getY(),0,displayH); selection.setX(start[0]); selection.setY(start[1]); selection.setWidth(0); selection.setHeight(0); });
        canvas.setOnMouseDragged(e -> {
            double x=clamp(e.getX(),0,displayW), y=clamp(e.getY(),0,displayH);
            selection.setX(Math.min(start[0],x)); selection.setY(Math.min(start[1],y));
            selection.setWidth(Math.abs(x-start[0])); selection.setHeight(Math.abs(y-start[1]));
        });

        Label note = new Label("Tarik mouse pada gambar untuk memilih area crop. Tanpa seleksi, seluruh gambar akan digunakan.");
        note.setWrapText(true);
        Button reset = new Button("Reset seleksi"); reset.setOnAction(e -> { selection.setX(0);selection.setY(0);selection.setWidth(0);selection.setHeight(0); });
        Button cancel = new Button("Batal");
        Button apply = new Button("Gunakan gambar"); apply.getStyleClass().add("button-primary");
        HBox actions = new HBox(8, reset, cancel, apply); actions.setPadding(new Insets(4,0,0,0));
        VBox content = new VBox(12, note, canvas, actions); content.setPadding(new Insets(18));
        Stage dialog = new Stage(); dialog.setTitle(title + " — Crop"); dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) dialog.initOwner(owner);
        dialog.setScene(new Scene(content));
        final String[] result = new String[1];
        cancel.setOnAction(e -> dialog.close());
        apply.setOnAction(e -> {
            try {
                double sx=selection.getX(), sy=selection.getY(), sw=selection.getWidth(), sh=selection.getHeight();
                if (sw < 4 || sh < 4) { sx=0;sy=0;sw=displayW;sh=displayH; }
                int ix=(int)Math.floor(sx/displayW*source.getWidth());
                int iy=(int)Math.floor(sy/displayH*source.getHeight());
                int iw=(int)Math.ceil(sw/displayW*source.getWidth());
                int ih=(int)Math.ceil(sh/displayH*source.getHeight());
                ix=Math.max(0,Math.min(ix,(int)source.getWidth()-1)); iy=Math.max(0,Math.min(iy,(int)source.getHeight()-1));
                iw=Math.max(1,Math.min(iw,(int)source.getWidth()-ix)); ih=Math.max(1,Math.min(ih,(int)source.getHeight()-iy));
                PixelReader reader=source.getPixelReader(); WritableImage cropped=new WritableImage(reader,ix,iy,iw,ih);
                BufferedImage buffered=new BufferedImage(iw,ih,BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<ih;y++) for(int x=0;x<iw;x++) buffered.setRGB(x,y,cropped.getPixelReader().getArgb(x,y));
                ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(buffered,"png",out);
                result[0]=Base64.getEncoder().encodeToString(out.toByteArray()); dialog.close();
            } catch (Exception ex) { note.setText("Crop gagal: " + ex.getMessage()); }
        });
        dialog.showAndWait();
        return result[0];
    }

    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}

    public static Image fromBase64(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            String clean=data.startsWith("data:")?data.substring(data.indexOf(',')+1):data;
            return new Image(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(clean)));
        } catch(Exception ex){return null;}
    }
}
