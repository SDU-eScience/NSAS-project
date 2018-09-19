/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import * as React from "react";
import { Pagination, Dropdown } from "semantic-ui-react";
import { createRange } from "../../UtilityFunctions";

interface PaginationButtons {
    totalPages: number,
    toPage: Function,
    currentPage: number,
    as?: string
    className?: string
}

export const Buttons = ({ className, totalPages, toPage, currentPage, as }: PaginationButtons) => (
    <Pagination
        as={as}
        className={className}
        totalPages={Math.max(1, totalPages)}
        activePage={currentPage + 1}
        onPageChange={(e, u) => toPage(u.activePage as number - 1)}
    />
);

const EntriesPerPageSelectorOptions = [
    { key: 1, text: "10", value: 10 },
    { key: 2, text: "25", value: 25 },
    { key: 3, text: "50", value: 50 },
    { key: 4, text: "100", value: 100 }
]

interface EntriesPerPageSelector {
    entriesPerPage: number,
    onChange: (number) => void,
    content?: string

    as?: string
    className?: string
}

export const EntriesPerPageSelector = ({
    as,
    className,
    entriesPerPage,
    onChange,
    content
}: EntriesPerPageSelector) => (
        <span className={className}>
            <span>{content} </span>
            <Dropdown
                compact
                inline
                as={as}
                onChange={(e, { value }) => onChange(value as number)}
                options={EntriesPerPageSelectorOptions}
                value={entriesPerPage}
            />
        </span>
    );
