package edu.syr.qdr.annorep.core.links;

import org.springframework.stereotype.Component;

@Component
public class DocumentLinks {
	
	public static final String CONVERTED_DOC = "/documents/{id}/pdf";
	public static final String ANNOTATION_DOC = "/documents/{id}/ann";
    public static final String CONVERT_DOC = "/documents/{id}";

}
