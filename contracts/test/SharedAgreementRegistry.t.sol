// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;
import "../src/SharedAgreementRegistry.sol";
interface Vm { function prank(address) external; function expectRevert() external; }
contract SharedAgreementRegistryTest {
    Vm constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
    SharedAgreementRegistry registry;
    address constant BOB = address(0xB0B);
    function setUp() public { registry = new SharedAgreementRegistry(); }
    function status(uint256 id) internal view returns (SharedAgreementRegistry.Status s) { (,,,s,,,) = registry.agreements(id); }
    function testMutualApproval() public {
        uint256 id = registry.propose(keccak256("salted-payload"), BOB, 0);
        require(status(id) == SharedAgreementRegistry.Status.Pending);
        vm.prank(BOB); registry.approve(id);
        require(status(id) == SharedAgreementRegistry.Status.Confirmed);
        vm.expectRevert(); vm.prank(BOB); registry.approve(id);
    }
    function testOnlyCounterpartyCanApprove() public {
        uint256 id = registry.propose(keccak256("one"), BOB, 0);
        vm.expectRevert(); registry.approve(id);
        vm.expectRevert(); vm.prank(address(0xBAD)); registry.approve(id);
    }
    function testReject() public {
        uint256 id = registry.propose(keccak256("one"), BOB, 0);
        vm.prank(BOB); registry.reject(id);
        require(status(id) == SharedAgreementRegistry.Status.Rejected);
        vm.expectRevert(); vm.prank(BOB); registry.approve(id);
    }
    function testInvalidAndDuplicateProposals() public {
        vm.expectRevert(); registry.propose(bytes32(0), BOB, 0);
        vm.expectRevert(); registry.propose(keccak256("one"), address(0), 0);
        vm.expectRevert(); registry.propose(keccak256("one"), address(this), 0);
        registry.propose(keccak256("one"), BOB, 0);
        vm.expectRevert(); registry.propose(keccak256("one"), BOB, 0);
    }
    function testCorrectionRequiresApprovalAndPreservesOriginal() public {
        uint256 id = registry.propose(keccak256("one"), BOB, 0);
        vm.prank(BOB); registry.approve(id);
        uint256 correction = registry.propose(keccak256("two"), BOB, id);
        require(status(id) == SharedAgreementRegistry.Status.Confirmed);
        vm.prank(BOB); registry.approve(correction);
        require(status(id) == SharedAgreementRegistry.Status.Superseded);
        require(registry.replacement(id) == correction);
        (bytes32 original,,,,,,) = registry.agreements(id);
        require(original == keccak256("one"));
    }
    function testRacingCorrectionsCannotBothConfirm() public {
        uint256 id = registry.propose(keccak256("one"), BOB, 0);
        vm.prank(BOB); registry.approve(id);
        uint256 a = registry.propose(keccak256("two"), BOB, id);
        uint256 b = registry.propose(keccak256("three"), BOB, id);
        vm.prank(BOB); registry.approve(a);
        vm.expectRevert(); vm.prank(BOB); registry.approve(b);
    }
}
