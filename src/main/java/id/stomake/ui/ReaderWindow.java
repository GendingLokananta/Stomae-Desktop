package id.stomake.ui;

import id.stomake.data.Repository;
import id.stomake.data.Repository.Chapter;
import id.stomake.data.Repository.Project;
import id.stomake.data.Repository.StoryRecord;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.SplitPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/** Separate distraction-free reading window with table of contents and fullscreen mode. */
public final class ReaderWindow {
    private record Section(String title,String html) {}
    private ReaderWindow() {}

    public static void open(Stage owner,Project project,Repository repo) throws Exception {
        List<Section> sections=new ArrayList<>();
        if(!project.coverImage().isBlank())sections.add(new Section("Sampul","<div style='text-align:center'><img style='max-width:65%;max-height:75vh' src='data:image/png;base64,"+project.coverImage()+"'></div><h1 style='text-align:center'>"+esc(project.title())+"</h1>"));
        if(!project.synopsis().isBlank())sections.add(new Section("Sinopsis","<h1>Sinopsis</h1><p>"+esc(project.synopsis()).replace("\n","</p><p>")+"</p>"));
        int i=1;
        for(Chapter c:repo.chapters(project.id())){
            String html=c.html()==null||c.html().isBlank()?"<p>"+esc(c.content()).replace("\n","</p><p>")+"</p>":c.html();
            StringBuilder media = new StringBuilder();
            if(c.imageData()!=null&&!c.imageData().isBlank()&&!html.contains(c.imageData())) media.append("<p><img style='display:block;max-width:80%;height:auto;margin:16px auto' src='data:image/png;base64,").append(c.imageData()).append("'></p>");
            try {
                var gallery = new com.fasterxml.jackson.databind.ObjectMapper().readTree(c.galleryData()==null?"[]":c.galleryData());
                if(gallery.isArray()) for(var item:gallery){
                    String data=item.path("data").asText("");
                    if(!data.isBlank()&&!html.contains(data)){
                        int width=(int)Math.max(10,Math.min(100,item.path("width").asDouble(1.0)*100));
                        String layout=item.path("layout").asText("center");
                        String align="left".equalsIgnoreCase(layout)?"float:left;margin:8px 18px 8px 0;":"right".equalsIgnoreCase(layout)?"float:right;margin:8px 0 8px 18px;":"display:block;margin:16px auto;";
                        media.append("<p><img style='max-width:").append(width).append("%;height:auto;").append(align).append("' src='data:image/png;base64,").append(data).append("'></p>");
                    }
                }
            }catch(Exception ignored){}
            sections.add(new Section("Bab "+i+" — "+c.title(),"<h1>"+esc(c.title())+"</h1>"+html+media));i++;
        }
        for(StoryRecord r:repo.storyRecords(project.id(),null)){
            String html="<h1>"+esc(r.name())+"</h1><p><b>"+esc(r.category())+"</b> · "+esc(r.role())+"</p><p>"+esc(r.details()).replace("\n","</p><p>")+"</p>";
            if(!r.imageData().isBlank())html+="<p><img style='max-width:70%' src='data:image/png;base64,"+r.imageData()+"'></p>";
            try { var gallery=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.galleryData()); if(gallery.isArray()) for(var im:gallery){String data=im.path("data").asText("");if(!data.isBlank())html+="<p><img style='max-width:"+(int)(im.path("width").asDouble(1.0)*100)+"%' src='data:image/png;base64,"+data+"'></p>";} } catch(Exception ignored){}
            sections.add(new Section(r.category()+" — "+r.name(),html));
        }
        var rels=repo.relationships(project.id());if(!rels.isEmpty()){StringBuilder html=new StringBuilder("<h1>Hubungan Karakter</h1>");for(var r:rels)html.append("<p><b>").append(esc(r.sourceName())).append("</b> — ").append(esc(r.relation())).append(" — <b>").append(esc(r.targetName())).append("</b><br>").append(esc(r.notes())).append("</p>");sections.add(new Section("Hubungan Karakter",html.toString()));}
        var timeline=repo.timeline(project.id());if(!timeline.isEmpty()){StringBuilder html=new StringBuilder("<h1>Timeline</h1>");for(var e:timeline)html.append("<p><b>").append(esc(e.date().isBlank()?"Tanpa tanggal":e.date())).append(" — ").append(esc(e.title())).append("</b><br>").append(esc(e.description())).append("</p>");sections.add(new Section("Timeline",html.toString()));}
        Stage reader=new Stage();reader.initOwner(owner);reader.setTitle("Mode Baca — "+project.title());
        Label title=new Label("✦  "+project.title());title.getStyleClass().add("section-heading");
        Button tocToggle=new Button("☰ Daftar Isi");Button fullscreen=new Button("⛶ Fullscreen");Button close=new Button("Tutup");
        HBox top=new HBox(10,title,spacer(),tocToggle,fullscreen,close);top.setAlignment(Pos.CENTER_LEFT);top.setPadding(new Insets(14,18,14,18));top.getStyleClass().add("topbar");
        ListView<String> toc=new ListView<>();for(Section section:sections)toc.getItems().add(section.title());toc.setPrefWidth(260);
        WebView view=new WebView();SplitPane split=new SplitPane(toc,view);split.setDividerPositions(.22);
        BorderPane root=new BorderPane(split);root.setTop(top);root.setStyle("-fx-background-color: #12131a;");
        toc.getSelectionModel().selectedIndexProperty().addListener((o,a,b)->{int index=b.intValue();if(index>=0&&index<sections.size())view.getEngine().loadContent(wrap(sections.get(index).html()));});
        if(!sections.isEmpty())toc.getSelectionModel().select(0);
        tocToggle.setOnAction(e->{boolean visible=toc.isVisible();toc.setVisible(!visible);toc.setManaged(!visible);split.setDividerPositions(visible?0:.22);tocToggle.setText(visible?"☰ Tampilkan Daftar Isi":"☰ Sembunyikan Daftar Isi");});
        fullscreen.setOnAction(e->{boolean state=!reader.isFullScreen();reader.setFullScreen(state);fullscreen.setText(state?"⛶ Keluar Fullscreen":"⛶ Fullscreen");});
        close.setOnAction(e->reader.close());
        reader.setScene(new Scene(root,1280,840));reader.setMinWidth(780);reader.setMinHeight(580);reader.show();reader.centerOnScreen();
    }
    private static Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private static String wrap(String html){return "<!doctype html><html><head><meta charset='utf-8'><style>body{font-family:Garamond,Georgia,serif;font-size:18px;line-height:1.85;max-width:920px;margin:52px auto;padding:0 42px;color:#292832;background:#fbfaf7}h1,h2,h3{line-height:1.25}img{height:auto;max-width:100%}p{margin:0 0 1em}</style></head><body>"+html+"</body></html>";}
    private static String esc(String s){return (s==null?"":s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
}
