package id.stomake.ui;

import id.stomake.data.Repository;
import id.stomake.data.Repository.Project;
import id.stomake.data.Repository.Relationship;
import id.stomake.data.Repository.StoryRecord;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/** JavaFX relationship manager and simple visual character network. */
public final class RelationshipPane extends BorderPane {
    private final Repository repo;
    private final ComboBox<Project> projects = new ComboBox<>();
    private final ComboBox<StoryRecord> source = new ComboBox<>();
    private final ComboBox<StoryRecord> target = new ComboBox<>();
    private final TextField relation = new TextField("Berteman");
    private final TextArea notes = new TextArea();
    private final ListView<Relationship> list = new ListView<>();
    private final Pane graph = new Pane();

    public RelationshipPane(Repository repo, long activeProjectId) throws Exception {
        this.repo=repo; getStyleClass().add("page-content"); setPadding(new Insets(20));
        Label heading=new Label("Hubungan Karakter");heading.getStyleClass().add("section-heading");
        Label subtitle=new Label("Atur dinamika antarkarakter dan lihat koneksinya dalam satu peta.");subtitle.getStyleClass().add("muted-label");
        VBox header=new VBox(4,heading,subtitle);header.setPadding(new Insets(0,0,12,0));setTop(header);
        projects.getItems().setAll(repo.projects());
        if(activeProjectId>0)projects.getItems().stream().filter(p->p.id()==activeProjectId).findFirst().ifPresent(projects::setValue);
        if(projects.getValue()==null&&!projects.getItems().isEmpty())projects.getSelectionModel().select(0);
        VBox editor=new VBox(10);editor.getStyleClass().add("panel-card");editor.setPrefWidth(310);
        Label formHeading=new Label("Tambah relasi");formHeading.getStyleClass().add("panel-heading");
        source.setMaxWidth(Double.MAX_VALUE);target.setMaxWidth(Double.MAX_VALUE);relation.setMaxWidth(Double.MAX_VALUE);
        notes.setPromptText("Catatan hubungan, sejarah, konflik, dinamika...");notes.setWrapText(true);notes.setPrefRowCount(4);
        Button add=new Button("＋ Simpan hubungan");add.getStyleClass().add("button-primary");add.setMaxWidth(Double.MAX_VALUE);add.setOnAction(e->addRelationship());
        editor.getChildren().addAll(new Label("Cerita"),projects,formHeading,new Label("Karakter A"),source,new Label("Karakter B"),target,new Label("Jenis hubungan"),relation,new Label("Catatan"),notes,add);
        VBox right=new VBox(12);right.getStyleClass().add("panel-card");
        Label graphTitle=new Label("PETA RELASI");graphTitle.getStyleClass().add("nav-section-title");graph.setMinHeight(300);graph.setPrefHeight(360);graph.setStyle("-fx-background-color: rgba(120,125,150,.06); -fx-background-radius: 14;");
        Label listTitle=new Label("DAFTAR HUBUNGAN");listTitle.getStyleClass().add("nav-section-title");VBox.setVgrow(list,Priority.ALWAYS);
        list.setCellFactory(v->new ListCell<>(){@Override protected void updateItem(Relationship r,boolean empty){super.updateItem(r,empty);setText(empty||r==null?null:r.sourceName()+"  —  "+r.relation()+"  →  "+r.targetName()+(r.notes().isBlank()?"":"\n"+r.notes()));}});
        Button remove=new Button("Hapus relasi terpilih");remove.getStyleClass().add("button-danger");remove.setOnAction(e->{Relationship r=list.getSelectionModel().getSelectedItem();if(r!=null)try{repo.deleteRelationship(r.id());refresh();}catch(Exception ex){error(ex);}});
        right.getChildren().addAll(graphTitle,graph,listTitle,list,remove);VBox.setVgrow(graph,Priority.ALWAYS);
        SplitPane split=new SplitPane(editor,right);split.setDividerPositions(.30);setCenter(split);
        projects.valueProperty().addListener((o,a,b)->refresh());refresh();
    }

    private void refresh(){
        try{
            Project p=projects.getValue();if(p==null){source.getItems().clear();target.getItems().clear();list.getItems().clear();graph.getChildren().clear();return;}
            List<StoryRecord> chars=repo.storyRecords(p.id(),"character");source.getItems().setAll(chars);target.getItems().setAll(chars);
            list.getItems().setAll(repo.relationships(p.id()));drawGraph(chars,repo.relationships(p.id()));
        }catch(Exception ex){error(ex);}
    }
    private void addRelationship(){
        Project p=projects.getValue();StoryRecord a=source.getValue(),b=target.getValue();
        if(p==null||a==null||b==null){alert("Pilih cerita dan dua karakter terlebih dahulu.");return;}
        try{repo.addRelationship(p.id(),a.id(),b.id(),relation.getText().trim(),notes.getText().trim());notes.clear();refresh();}
        catch(Exception ex){error(ex);}
    }
    private void drawGraph(List<StoryRecord> chars,List<Relationship> rels){
        graph.getChildren().clear();double w=760,h=350,cx=w/2,cy=h/2,r=Math.min(130,Math.max(70,chars.size()*19));
        if(chars.isEmpty()){Label empty=new Label("Tambahkan karakter di Story Bible untuk membuat peta relasi.");empty.getStyleClass().add("muted-label");graph.getChildren().add(empty);return;}
        List<double[]> points=new ArrayList<>();
        for(int i=0;i<chars.size();i++){double angle=(2*Math.PI*i/Math.max(1,chars.size()))-Math.PI/2;points.add(new double[]{cx+r*Math.cos(angle),cy+r*Math.sin(angle)});}
        for(Relationship edge:rels){int a=indexOf(chars,edge.sourceId()),b=indexOf(chars,edge.targetId());if(a>=0&&b>=0){double[] p1=points.get(a),p2=points.get(b);Line line=new Line(p1[0],p1[1],p2[0],p2[1]);line.setStroke(Color.web("#7885a8"));line.setStrokeWidth(2);graph.getChildren().add(line);}}
        for(int i=0;i<chars.size();i++){StoryRecord c=chars.get(i);double[] p=points.get(i);Circle circle=new Circle(p[0],p[1],26,Color.web("#393852"));circle.setStroke(Color.web("#a59be7"));circle.setStrokeWidth(2);Text initial=new Text(c.name().isBlank()?"?":c.name().substring(0,1).toUpperCase());initial.setFill(Color.WHITE);initial.setX(p[0]-4);initial.setY(p[1]+5);Text label=new Text(trim(c.name(),22));label.setFill(Color.web("#deddf0"));label.setX(p[0]-Math.min(70,label.getLayoutBounds().getWidth()/2+3));label.setY(p[1]+47);graph.getChildren().addAll(circle,initial,label);}
        graph.setPrefWidth(w);graph.setPrefHeight(h);
    }
    private int indexOf(List<StoryRecord> list,long id){for(int i=0;i<list.size();i++)if(list.get(i).id()==id)return i;return -1;}
    private String trim(String x,int n){return x.length()>n?x.substring(0,n-1)+"…":x;}
    private void alert(String s){new Alert(Alert.AlertType.INFORMATION,s,ButtonType.OK).showAndWait();}
    private void error(Exception ex){new Alert(Alert.AlertType.ERROR,ex.getMessage(),ButtonType.OK).showAndWait();}
}
