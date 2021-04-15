package edu.syr.qdr.annorep.core.controller;

import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import edu.syr.qdr.annorep.core.entity.Documents;
import edu.syr.qdr.annorep.core.links.DocumentLinks;
import edu.syr.qdr.annorep.core.service.DataverseService;
import edu.syr.qdr.annorep.core.service.DocumentsService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/")
public class DocumentsController {

    @Autowired
    DocumentsService documentsService;

    @Autowired
    DataverseService dataverseService;

    @ExceptionHandler
    void handleIllegalArgumentException(IllegalArgumentException e, HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.CONFLICT.value(), "Already Converted");
    }

    @GetMapping(path = DocumentLinks.CONVERTED_DOC)
    public ResponseEntity<?> getConvertedDocument(@PathVariable long id) {
        log.info("DocumentsController:  get pdf");
        HttpHeaders headers = new HttpHeaders();
        headers.add("Location", dataverseService.getPdfUrl(id));
        return new ResponseEntity<String>(headers, HttpStatus.FOUND);
    }

    @GetMapping(path = DocumentLinks.ANNOTATION_DOC)
    public ResponseEntity<?> getAnnotationDocument(@PathVariable long id) {
        log.info("DocumentsController:  get ann");

        HttpHeaders headers = new HttpHeaders();
        headers.add("Location", dataverseService.getAnnUrl(id));
        return new ResponseEntity<String>(headers, HttpStatus.FOUND);
    }

    @PutMapping(path = DocumentLinks.CONVERT_DOC)
    public ResponseEntity<?> convertDoc(@RequestHeader(name = "X-Dataverse-key") String apikey, @PathVariable long id) {
        log.info("DocumentsController:  convert doc, id: " + id + " " + apikey);
        Documents resource = documentsService.convertDoc(id, apikey);
        log.info("Back from service");
        if (resource == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Could not find datafile");
        }
        if (resource.isConverted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Converted files already exist for the specified version");
        }

        return ResponseEntity.ok(resource.getId());
    }

    @DeleteMapping(path = DocumentLinks.CONVERT_DOC)
    public ResponseEntity<?> deleteConvertedDocument(@RequestHeader(name = "X-Dataverse-key") String apikey, @PathVariable long id) {
        log.info("DocumentsController:  delete pdf and ann");
        int status = documentsService.deleteDoc(id, apikey);
        if (status == 200) {
            return new ResponseEntity<String>(HttpStatus.NO_CONTENT);
        } else {
            return new ResponseEntity<String>(HttpStatus.valueOf(status));
        }
    }


}
