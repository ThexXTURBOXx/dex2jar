package com.googlecode.d2j;

/**
 * represent a field_id_item in dex file format
 *
 * @author <a href="mailto:pxb1988@gmail.com">Panxiaobo</a>
 * @version $Rev$
 */
public class Field {

    private final String name;

    private final String owner;

    private final String type;

    public Field(String owner, String name, String type) {
        this.owner = owner;
        this.type = type;
        this.name = name;
    }

    /**
     * @return name of the field.
     */
    public String getName() {
        return name;
    }

    /**
     * @return owner of the field, in TypeDescriptor format.
     */
    public String getOwner() {
        return owner;
    }

    /**
     * @return type of the field, in TypeDescriptor format.
     */
    public String getType() {
        return type;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return this.getOwner() + "->" + this.getName() + ":" + this.getType();
    }

}
