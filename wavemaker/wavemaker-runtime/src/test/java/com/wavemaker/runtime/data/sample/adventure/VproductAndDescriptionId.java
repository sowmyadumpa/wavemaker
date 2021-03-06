/*
 *  Copyright (C) 2009 WaveMaker Software, Inc.
 *
 *  This file is part of the WaveMaker Server Runtime.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wavemaker.runtime.data.sample.adventure;

// Generated Aug 18, 2007 5:20:14 PM by Hibernate Tools 3.2.0.b9

/**
 * VproductAndDescriptionId generated by hbm2java
 */
@SuppressWarnings("serial")
public class VproductAndDescriptionId implements java.io.Serializable {

    private int productId;

    private String name;

    private String productModel;

    private String culture;

    private String description;

    public VproductAndDescriptionId() {
    }

    public VproductAndDescriptionId(int productId, String name, String productModel, String culture, String description) {
        this.productId = productId;
        this.name = name;
        this.productModel = productModel;
        this.culture = culture;
        this.description = description;
    }

    public int getProductId() {
        return this.productId;
    }

    public void setProductId(int productId) {
        this.productId = productId;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProductModel() {
        return this.productModel;
    }

    public void setProductModel(String productModel) {
        this.productModel = productModel;
    }

    public String getCulture() {
        return this.culture;
    }

    public void setCulture(String culture) {
        this.culture = culture;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (!(other instanceof VproductAndDescriptionId)) {
            return false;
        }
        VproductAndDescriptionId castOther = (VproductAndDescriptionId) other;

        return this.getProductId() == castOther.getProductId()
            && (this.getName() == castOther.getName() || this.getName() != null && castOther.getName() != null
                && this.getName().equals(castOther.getName()))
            && (this.getProductModel() == castOther.getProductModel() || this.getProductModel() != null && castOther.getProductModel() != null
                && this.getProductModel().equals(castOther.getProductModel()))
            && (this.getCulture() == castOther.getCulture() || this.getCulture() != null && castOther.getCulture() != null
                && this.getCulture().equals(castOther.getCulture()))
            && (this.getDescription() == castOther.getDescription() || this.getDescription() != null && castOther.getDescription() != null
                && this.getDescription().equals(castOther.getDescription()));
    }

    @Override
    public int hashCode() {
        int result = 17;

        result = 37 * result + this.getProductId();
        result = 37 * result + (getName() == null ? 0 : this.getName().hashCode());
        result = 37 * result + (getProductModel() == null ? 0 : this.getProductModel().hashCode());
        result = 37 * result + (getCulture() == null ? 0 : this.getCulture().hashCode());
        result = 37 * result + (getDescription() == null ? 0 : this.getDescription().hashCode());
        return result;
    }

}
