package edu.syr.qdr.annorep.core.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;

import edu.syr.qdr.annorep.core.util.Annotation;
import lombok.Data;

@Entity
@Data
public class Documents {

    @Id
    @Column
    private long id;

    @Column
    private boolean converted;

    @Column
    private String mimetype;
    
    @Column(length=2048) 
    private String titleAnnotation; 
    
    @Lob
    @Column
    private String annotations;
}
