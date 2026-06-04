package org.sterl.llmpeon.command;

import java.nio.file.Path;

import org.sterl.llmpeon.shared.AbstractPromptFile;

public class CommandPromptFile extends AbstractPromptFile {

    private String slug;

    public CommandPromptFile(String name, String description, Path promptFile) {
        super(name, description, promptFile, true);
    }

    public String slug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    @Override
    public String shortDescription() {
        return "Command[name=" + name() + (description() == null ? "" : ", description=" + description()) + "]";
    }
}