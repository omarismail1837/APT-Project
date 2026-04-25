package App;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.Date;

//this class is used for each user to view their documents
@Document(collection = "documents")
public class DocMetadata {

    @Id
    private String docId;

    private String name;
    private String ownerId;

    // These are the "Active" codes.
    // They change whenever a new session starts after a 5-minute cold period.
    private String editCode;
    private String viewCode;

    private Date lastModified;
    private Date createdAt;

    public DocMetadata() {
        this.createdAt = new Date();
        this.lastModified = new Date();
    }

    // Standard Getters and Setters
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getEditCode() { return editCode; }
    public void setEditCode(String editCode) { this.editCode = editCode; }

    public String getViewCode() { return viewCode; }
    public void setViewCode(String viewCode) { this.viewCode = viewCode; }

    public Date getLastModified() { return lastModified; }
    public void setLastModified(Date lastModified) { this.lastModified = lastModified; }

    public Date getCreatedAt() { return createdAt; }
}