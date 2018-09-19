/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import * as React from "react";
import { Icon } from "semantic-ui-react";

export const FileIcon = ({ name, size, link = false, color }) =>
    link ?
        <Icon.Group size={size}>
            <Icon name={name} color={color} />
            <Icon corner color="grey" name="share" />
        </Icon.Group> :
        <Icon name={name} size={size} color={color} />
