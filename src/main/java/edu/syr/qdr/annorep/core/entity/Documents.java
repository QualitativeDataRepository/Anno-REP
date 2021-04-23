package edu.syr.qdr.annorep.core.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
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
}
