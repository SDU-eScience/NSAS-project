/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import * as React from "react";
import { Breadcrumb } from "semantic-ui-react";
import "./Breadcrumbs.scss";
import { Cloud } from "../../../authentication/SDUCloudObject";

interface Breadcrumbs { currentPath: string, navigate: (path: string) => void }
export const BreadCrumbs = ({ currentPath, navigate }: Breadcrumbs) => {
    if (!currentPath) {
        return null;
    }
    const pathsMapping = buildBreadCrumbs(currentPath);
    const activePathsMapping = pathsMapping.pop()
    const breadcrumbs = pathsMapping.map((path, index) => (
        <React.Fragment key={index}>
            <Breadcrumb.Section onClick={() => navigate(`${path.actualPath}`)} link>
                {path.local}
            </Breadcrumb.Section>
            <Breadcrumb.Divider />
        </React.Fragment>
    ));

    return (
        <Breadcrumb className="breadcrumb-margin">
            {breadcrumbs}
            <Breadcrumb.Section active>
                {activePathsMapping.local}
            </Breadcrumb.Section>
        </Breadcrumb>
    );

}

type BreadCrumbMapping = {
    actualPath: string
    local: string
}

export function buildBreadCrumbs(path: string) {
    const paths = path.split("/").filter((path: string) => path);
    let pathsMapping = [];
    for (let i = 0; i < paths.length; i++) {
        let actualPath = "/";
        for (let j = 0; j <= i; j++) {
            actualPath += paths[j] + "/";
        }
        pathsMapping.push({ actualPath: actualPath, local: paths[i] });
    }
    if (path.includes(Cloud.homeFolder)) { // remove first two indices 
        pathsMapping = 
            [{ actualPath: Cloud.homeFolder, local: "Home" }].concat(pathsMapping.slice(2, pathsMapping.length));
    }
    return pathsMapping;
}
