package edu.syr.qdr.annorep.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import edu.syr.qdr.annorep.core.entity.Documents;

@RepositoryRestResource()
public interface DocumentsRepository extends JpaRepository<Documents, Long>, JpaSpecificationExecutor<Documents>, QuerydslPredicateExecutor<Documents> {}
