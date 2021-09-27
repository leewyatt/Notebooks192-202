/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.itcodebox.notebooks.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * "name","update_time","show_order"
 *
 * @author Administrator
 */
public class Chapter extends Record  {

    private Integer notebookId;

    public Chapter() {
    }

    public Chapter(Integer notebookId, String title,Long createTime) {
        this(notebookId,title,createTime,createTime);
    }
    public Chapter(Integer notebookId, String title,Long createTime, Long updateTime) {
        this.notebookId = notebookId;
        this.title = title;
        this.createTime =createTime;
        this.updateTime = updateTime;
    }



    public Integer getNotebookId() {
        return notebookId;
    }

    public void setNotebookId(Integer notebookId) {
        this.notebookId = notebookId;
    }
    @Override
    public String toString() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return "";
    }

    public Chapter(String jsonStr) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            Chapter chapter = objectMapper.readValue(jsonStr, Chapter.class);
            this.id = chapter.getId();
            this.createTime = chapter.getCreateTime();
            this.title = chapter.getTitle();
            this.updateTime = chapter.getUpdateTime();
            this.showOrder = chapter.getShowOrder();
            this.notebookId = chapter.getNotebookId();
        //高版本这里是一个异常JsonProcessingException, 现在这里要把JsonProcessingException和IOException两个异常, 合并为一个IO异常
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Chapter chapter = (Chapter) o;
        return Objects.equals(id, chapter.id) && Objects.equals(notebookId, chapter.notebookId) && Objects.equals(title, chapter.title) && Objects.equals(updateTime, chapter.updateTime) && Objects.equals(showOrder, chapter.showOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, notebookId, title, updateTime, showOrder);
    }


}
