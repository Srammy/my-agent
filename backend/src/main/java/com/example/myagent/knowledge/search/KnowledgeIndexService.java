package com.example.myagent.knowledge.search;

import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexService {

  private final KnowledgeElasticsearchIndexManager indexManager;
  private final KnowledgeEmbeddingService embeddingService;

  public KnowledgeIndexService(
      KnowledgeElasticsearchIndexManager indexManager, KnowledgeEmbeddingService embeddingService) {
    this.indexManager = indexManager;
    this.embeddingService = embeddingService;
  }

  public void index(KnowledgeDocumentContent content) {
    indexManager.deleteByDocument(content.userId(), content.documentId());
    List<KnowledgeParentDocument> parents = new ArrayList<>();
    List<KnowledgeChildDocument> children = new ArrayList<>();
    for (KnowledgeDocumentContent.ParentDocument parent : content.parents()) {
      parents.add(
          new KnowledgeParentDocument(
              parent.parentId(),
              content.userId(),
              content.documentId(),
              content.sourceFilename(),
              content.contentType(),
              parent.parentIndex(),
              parent.pageNumber(),
              parent.text(),
              "READY"));
      for (KnowledgeDocumentContent.ChildDocument child : parent.children()) {
        float[] vector = embeddingService.embed(child.text());
        children.add(
            new KnowledgeChildDocument(
                child.childId(),
                content.userId(),
                content.documentId(),
                child.parentId(),
                content.sourceFilename(),
                content.contentType(),
                child.childIndex(),
                child.pageNumber(),
                child.text(),
                toList(vector),
                "READY"));
      }
    }
    indexManager.writeParents(parents);
    indexManager.writeChildren(children);
  }

  private static List<Float> toList(float[] vector) {
    List<Float> values = new ArrayList<>(vector.length);
    for (float value : vector) values.add(value);
    return values;
  }
}
