package App;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

// didnt add content to docmetadata bec it would be too heavy
@Document(collection = "document_content")
public class DocContent {
    @Id
    private String docId;

    private String content;

    public DocContent() {}

    public DocContent(String docId, String content) {
        this.docId = docId;
        this.content = content;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
