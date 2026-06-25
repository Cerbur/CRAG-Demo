package ai.cerbur.crag.knowledge.grpc.provider;

import ai.cerbur.crag.contracts.knowledge.v1.Document;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentFileChunk;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentFileMetadata;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentServiceGrpc;
import ai.cerbur.crag.contracts.knowledge.v1.GetDocumentRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListDocumentsRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListDocumentsResponse;
import ai.cerbur.crag.contracts.knowledge.v1.ReadDocumentFileRequest;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentMetadata;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentRequest;
import ai.cerbur.crag.knowledge.core.document.DocumentQueryService;
import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import ai.cerbur.crag.knowledge.core.document.DocumentUploadCommand;
import ai.cerbur.crag.knowledge.core.document.DocumentUploadService;
import ai.cerbur.crag.knowledge.core.document.FileType;
import ai.cerbur.crag.knowledge.core.document.UploadHandle;
import ai.cerbur.crag.knowledge.core.file.FileRead;
import ai.cerbur.crag.knowledge.core.file.FileReadService;
import ai.cerbur.crag.knowledge.grpc.error.GrpcErrorMapper;
import ai.cerbur.crag.knowledge.grpc.mapper.DocumentMapper;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Document gRPC provider：单次客户端流式上传、查询、列表与文件 server streaming 读取。只做协议暴露、proto 映射与错误映射； 业务复用 {@link
 * DocumentUploadService}、{@link DocumentQueryService} 与 {@link FileReadService}。读取响应不含 storage key
 * 或路径。
 */
@Component
public class DocumentGrpcProvider extends DocumentServiceGrpc.DocumentServiceImplBase {

  private static final int CHUNK_SIZE = 8192;

  @Autowired private DocumentUploadService uploadService;
  @Autowired private DocumentQueryService queryService;
  @Autowired private FileReadService fileReadService;

  @Override
  public StreamObserver<UploadDocumentRequest> uploadDocument(
      StreamObserver<Document> responseObserver) {
    return new StreamObserver<>() {
      private UploadHandle handle;
      private boolean failed;

      @Override
      public void onNext(UploadDocumentRequest request) {
        if (failed) {
          return;
        }
        try {
          switch (request.getRequestCase()) {
            case METADATA -> {
              if (handle != null) {
                throw new IllegalArgumentException("metadata already received");
              }
              UploadDocumentMetadata metadata = request.getMetadata();
              handle =
                  uploadService.begin(
                      new DocumentUploadCommand(
                          DecimalId.parse(metadata.getTenantId(), "tenant_id"),
                          DecimalId.parse(metadata.getKnowledgeBaseId(), "knowledge_base_id"),
                          DecimalId.parse(metadata.getUploadedByUserId(), "uploaded_by_user_id"),
                          metadata.getOriginalFilename(),
                          FileType.fromDeclared(metadata.getFileType()),
                          metadata.getSizeBytes(),
                          metadata.getSha256()));
            }
            case CHUNK -> {
              if (handle == null) {
                throw new IllegalArgumentException("metadata must precede chunks");
              }
              ByteString chunk = request.getChunk();
              uploadService.append(handle, chunk.toByteArray(), 0, chunk.size());
            }
            case REQUEST_NOT_SET -> {
              // ignore unknown oneof
            }
          }
        } catch (RuntimeException e) {
          failed = true;
          abortQuietly();
          responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
        }
      }

      @Override
      public void onCompleted() {
        if (failed) {
          return;
        }
        if (handle == null) {
          responseObserver.onError(
              Status.INVALID_ARGUMENT.withDescription("metadata missing").asRuntimeException());
          return;
        }
        try {
          DocumentResult result = uploadService.complete(handle);
          handle = null;
          responseObserver.onNext(DocumentMapper.toProto(result));
          responseObserver.onCompleted();
        } catch (RuntimeException e) {
          abortQuietly();
          responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
        }
      }

      @Override
      public void onError(Throwable error) {
        failed = true;
        abortQuietly();
      }

      private void abortQuietly() {
        if (handle != null) {
          uploadService.abort(handle);
          handle = null;
        }
      }
    };
  }

  @Override
  public void getDocument(GetDocumentRequest request, StreamObserver<Document> responseObserver) {
    try {
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      long docId = DecimalId.parse(request.getDocId(), "doc_id");
      Optional<DocumentResult> result = queryService.get(docId, tenantId);
      if (result.isEmpty()) {
        responseObserver.onError(
            Status.NOT_FOUND.withDescription("document not found").asRuntimeException());
        return;
      }
      responseObserver.onNext(DocumentMapper.toProto(result.get()));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void listDocuments(
      ListDocumentsRequest request, StreamObserver<ListDocumentsResponse> responseObserver) {
    try {
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      long knowledgeBaseId = DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id");
      int pageSize = normalizePageSize(request.getPageSize());
      var results = queryService.list(knowledgeBaseId, tenantId, PageRequest.ofSize(pageSize));
      var builder = ListDocumentsResponse.newBuilder();
      results.forEach(result -> builder.addDocuments(DocumentMapper.toProto(result)));
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void readDocumentFile(
      ReadDocumentFileRequest request, StreamObserver<DocumentFileChunk> responseObserver) {
    Optional<FileRead> read;
    try {
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      long docId = DecimalId.parse(request.getDocId(), "doc_id");
      read = fileReadService.open(docId, tenantId);
    } catch (RuntimeException e) {
      responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
      return;
    }
    if (read.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND.withDescription("document not found").asRuntimeException());
      return;
    }
    FileRead fileRead = read.get();
    responseObserver.onNext(
        DocumentFileChunk.newBuilder()
            .setMetadata(
                DocumentFileMetadata.newBuilder()
                    .setSizeBytes(fileRead.sizeBytes())
                    .setSha256(fileRead.sha256())
                    .setFileType(fileRead.fileType().name())
                    .build())
            .build());
    try (InputStream input = fileRead.content()) {
      byte[] buffer = new byte[CHUNK_SIZE];
      int readBytes;
      while ((readBytes = input.read(buffer)) >= 0) {
        if (readBytes == 0) {
          continue;
        }
        responseObserver.onNext(
            DocumentFileChunk.newBuilder()
                .setData(ByteString.copyFrom(buffer, 0, readBytes))
                .build());
      }
      responseObserver.onCompleted();
    } catch (IOException e) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to read document file").asRuntimeException());
    }
  }

  private static int normalizePageSize(int requested) {
    return requested <= 0 ? 50 : Math.min(requested, 200);
  }
}
