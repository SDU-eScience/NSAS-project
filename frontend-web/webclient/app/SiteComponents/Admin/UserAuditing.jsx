/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import React from "react";
import LoadingIcon from "../LoadingIcon/LoadingIcon";
import {Button} from "semantic-ui-react";

class UserAuditing extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            userId: props.params.id,
            activityCards: [],
        }
    }
    
    render() {
        return (
            <section>
                <select>
                    <option value="any" selected>Any</option>
                    <option value="hpcjobs">HPC Jobs</option>
                    <option value="files">Files</option>
                </select>
                <Button>Search</Button>
                <ActivityCard/>
                <ActivityEvents/>
            </section>
        )
    }
}

function ActivityCard(props) {
    return (null);
}

function ActivityEvents(props) {
    return (null);
}

export default UserAuditing;
