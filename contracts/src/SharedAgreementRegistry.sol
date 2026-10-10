// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @notice Proof of mutual acknowledgment. No money is transferred by this contract.
/// @dev Hash a canonical versioned payload with a random salt off-chain. Never publish financial details.
contract SharedAgreementRegistry {
    enum Status { Pending, Confirmed, Rejected, Superseded }
    struct Agreement {
        bytes32 payloadHash;
        address proposer;
        address counterparty;
        Status status;
        uint64 createdAt;
        uint64 confirmedAt;
        uint256 supersedes;
    }
    uint256 public nextId = 1;
    mapping(uint256 => Agreement) public agreements;
    mapping(bytes32 => bool) public usedHashes;
    mapping(uint256 => uint256) public replacement;

    event Proposed(uint256 indexed id, bytes32 indexed payloadHash, address indexed proposer, address counterparty, uint256 supersedes);
    event Confirmed(uint256 indexed id, bytes32 indexed payloadHash);
    event Rejected(uint256 indexed id);
    event Superseded(uint256 indexed oldId, uint256 indexed newId);

    function propose(bytes32 payloadHash, address counterparty, uint256 supersedes) external returns (uint256 id) {
        require(payloadHash != bytes32(0) && !usedHashes[payloadHash], "Invalid or duplicate hash");
        require(counterparty != address(0) && counterparty != msg.sender, "Invalid counterparty");
        if (supersedes != 0) {
            Agreement storage old = agreements[supersedes];
            require(old.status == Status.Confirmed, "Original not confirmed");
            require((old.proposer == msg.sender && old.counterparty == counterparty) || (old.counterparty == msg.sender && old.proposer == counterparty), "Different participants");
        }
        id = nextId++;
        usedHashes[payloadHash] = true;
        agreements[id] = Agreement(payloadHash, msg.sender, counterparty, Status.Pending, uint64(block.timestamp), 0, supersedes);
        emit Proposed(id, payloadHash, msg.sender, counterparty, supersedes);
    }
    function approve(uint256 id) external {
        Agreement storage a = agreements[id];
        require(msg.sender == a.counterparty, "Only counterparty");
        require(a.status == Status.Pending, "Not pending");
        if (a.supersedes != 0) {
            Agreement storage old = agreements[a.supersedes];
            require(old.status == Status.Confirmed, "Original already replaced");
            old.status = Status.Superseded;
            replacement[a.supersedes] = id;
            emit Superseded(a.supersedes, id);
        }
        a.status = Status.Confirmed;
        a.confirmedAt = uint64(block.timestamp);
        emit Confirmed(id, a.payloadHash);
    }
    function reject(uint256 id) external {
        Agreement storage a = agreements[id];
        require(msg.sender == a.counterparty, "Only counterparty");
        require(a.status == Status.Pending, "Not pending");
        a.status = Status.Rejected;
        emit Rejected(id);
    }
}
