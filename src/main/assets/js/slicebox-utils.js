angular.module('slicebox.utils', [])

.factory('openConfirmationDeleteModal', function($modal) {

    return function(title, message, deleteCallback) {

        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/confirmDeleteModalContent.html',
                controller: 'SbxConfirmDeleteModalController',
                resolve: {
                        title: function() {
                            return title;
                        },
                        message: function() {
                            return message;
                        },
                        deleteCallback: function() {
                            return deleteCallback;
                        }
                    }
            });

        return modalInstance.result;
    };
})

.controller('SbxConfirmDeleteModalController', function ($scope, $q, $modalInstance, title, message, deleteCallback) {
    $scope.title = title;
    $scope.message = message;

    $scope.uiState = {
        deleteInProgress: false
    };

    $scope.deleteButtonClicked = function () {
        var deletePromise = deleteCallback();

        $scope.uiState.deleteInProgress = true;

        deletePromise.finally(function() {
            $scope.uiState.deleteInProgress = false;
            $modalInstance.close();
        });
    };    

    $scope.cancelButtonClicked = function () {
        $modalInstance.close();
    };
});