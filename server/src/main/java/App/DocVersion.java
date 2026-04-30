package App;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Document(collection = "document_versions")
public class DocVersion {

    @Id
    private String id;

    @Indexed
    private String docId;

    private String label;
    private List<App.crdt.action.Action> actions;
    private String contentPreview;
    private Date createdAt;
    private String createdBy;

    public DocVersion() {}

    public DocVersion(String docId, String label,
                      List<App.crdt.action.Action> actions,
                      String contentPreview,
                      String createdBy) {
        this.docId          = docId;
        this.label          = label;
        this.actions        = actions;
        this.contentPreview = contentPreview;
        this.createdBy      = createdBy;
        this.createdAt      = new Date();
    }

    public String getId()             { return id; }
    public String getDocId()          { return docId; }
    public String getLabel()          { return label; }
    public List<App.crdt.action.Action> getActions() { return actions; }
    public String getContentPreview() { return contentPreview; }
    public Date   getCreatedAt()      { return createdAt; }
    public String getCreatedBy()      { return createdBy; }

    public void setId(String id)                     { this.id = id; }
    public void setDocId(String docId)               { this.docId = docId; }
    public void setLabel(String label)               { this.label = label; }
    public void setActions(List<App.crdt.action.Action> actions) { this.actions = actions; }
    public void setContentPreview(String p)          { this.contentPreview = p; }
    public void setCreatedAt(Date d)                 { this.createdAt = d; }
    public void setCreatedBy(String u)               { this.createdBy = u; }
}